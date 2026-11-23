import { cellRef, isAnchor, rangeRef } from '../../excel/cellRef';
import type { MergedRange, SheetView } from '../../excel/types';
import type { LessonType, ParsedEntry, ParsedGroup, WeekType } from '../../model/types';
import type { DiagnosticsCollector, SourceLocation } from '../../parser/diagnostics';
import { formatTime, isBlank, minutesOf } from '../../util/text';
import type { CellContent, GridFormat, LessonTextParser } from './format';
import { courseOf } from './groupCodeYear';

/**
 * Разбор одного листа «сетки» расписания ВГУИТ: группы по столбцам, дни и двухстрочные временные слоты по строкам.
 * Номера строк и столбцов нигде не зашиты. Шапка находится по подписи «Дни», группы и ширина берутся из объединённых ячеек
 * строки групп, дни и слоты из объединённых диапазонов в столбцах подписей, неделя и подгруппа занятия из геометрии его
 * объединённого диапазона внутри прямоугольника «слот × группа».
 *
 *   слот (2 строки)             группа из 2 столбцов
 *   верхняя строка = числитель  левый столбец  = подгруппа 1
 *   нижняя строка = знаменатель правый столбец = подгруппа 2
 *
 * Диапазон на обе строки это занятие каждую неделю, на все столбцы группы это занятие всей группы, на несколько целых соседних
 * групп это общее занятие (повторяется для каждой группы). Скрытые в Excel столбцы и строки не читаются: их не видят люди
 * (в файлах СПО в них лежат устаревшие группы и занятия).
 * Перенос Java-класса GridSheetParser: поведение и сообщения сохранены, сверка по эталонной выгрузке в tests/.
 */

const DAYS_LABEL = /^\s*дни(\s+недели)?\s*$/i;
const TIME_LABEL = /^\s*(часы(\s+звонков)?|время)\s*$/i;
const HEADER_SEARCH_LIMIT = 100;
const EXPECTED_SLOT_HEIGHT = 2;
/** Длина одной пары. Каждый явный интервал «начало-конец» в реальных файлах ровно такой. */
const LESSON_MINUTES = 95;

const DAYS: Record<string, number> = {
  ПОНЕДЕЛЬНИК: 1,
  ВТОРНИК: 2,
  СРЕДА: 3,
  ЧЕТВЕРГ: 4,
  ПЯТНИЦА: 5,
  СУББОТА: 6,
  ВОСКРЕСЕНЬЕ: 7,
};
const TIME_RANGE = /(\d{1,2})[.: ](\d{2})\s*[-–]\s*(\d{1,2})[.: ](\d{2})/;
/** Только начало, возможно с номером: «1) 8 00». */
const TIME_START = /^\s*(?:\d+\)\s*)?(\d{1,2})[.: ](\d{2})\s*$/;
const SUBGROUP_IN_TEXT = /(\d+)\s*п\/г/;
/** «2 КУРС», «1  курса», «1, 2 и 5 курсов». Число, приклеенное к слову или цифре слева, не считается (проверка руками, без lookbehind). */
const COURSE = /((?:[1-6]\s*(?:,|и)\s*)*[1-6])(?:\s*-?\s*(?:й|го|ого))?\s*курс/gi;
const COURSE_DIGIT = /[1-6]/g;
const LETTER_OR_DIGIT = /[0-9A-Za-zА-Яа-яЁё]/;

/** Видимые столбцы группы по возрастанию. Подгруппы нумеруются среди видимых столбцов. */
interface GroupColumns {
  code: string;
  columns: number[];
}
const firstCol = (g: GroupColumns): number => g.columns[0];
const lastCol = (g: GroupColumns): number => g.columns[g.columns.length - 1];
const subgroupIndex = (g: GroupColumns, col: number): number => g.columns.indexOf(col) + 1;

interface TimeSlot {
  firstRow: number;
  lastRow: number;
  startMin: number;
  endMin: number;
}
const slotHeight = (s: TimeSlot): number => s.lastRow - s.firstRow + 1;

interface Header {
  labelRow: number;
  dayCol: number;
  timeCol: number;
  groupRow: number;
  firstDataRow: number;
}

interface CourseMention {
  courses: number[];
  firstCol: number;
  lastCol: number;
}
const namesOneCourseAt = (m: CourseMention, col: number): boolean => m.courses.length === 1 && col >= m.firstCol && col <= m.lastCol;

interface GroupSpan {
  group: GroupColumns;
  full: boolean;
}

export interface SheetResult {
  groups: ParsedGroup[];
  entries: ParsedEntry[];
}

/** Номер строки (с нуля) со словом «Дни» или -1, если на листе его нет. */
export function findHeaderRow(sheet: SheetView): number {
  const limit = Math.min(sheet.lastRow, HEADER_SEARCH_LIMIT);
  for (let r = sheet.firstRow; r <= limit; r++) {
    for (const c of sheet.cellColumns(r)) {
      if (DAYS_LABEL.test(sheet.text(r, c))) return r;
    }
  }
  return -1;
}

/** Номера курсов в тексте: «2 КУРС» → [2], «1, 2 и 5 курсов» → [1, 2, 5], «Расписание» → []. */
export function coursesIn(text: string): number[] {
  const found = new Set<number>();
  const re = new RegExp(COURSE.source, COURSE.flags);
  let m: RegExpExecArray | null;
  while ((m = re.exec(text)) !== null) {
    if (m.index > 0 && LETTER_OR_DIGIT.test(text[m.index - 1])) {
      re.lastIndex = m.index + 1; // число приклеено слева: не курс; ищем дальше со следующей позиции
      continue;
    }
    for (const d of m[1].match(COURSE_DIGIT) ?? []) found.add(Number(d));
  }
  return [...found].sort((a, b) => a - b);
}

export class GridSheetParser {
  private readonly textParser: LessonTextParser;
  private sheet!: SheetView;
  private allGroups: GroupColumns[] = [];
  private slotsWithDerivedEnd = 0;
  private firstDerivedEnd: SourceLocation | null = null;
  private hiddenGroups: string[] = [];
  private hiddenCells = 0;
  private firstHiddenCell: SourceLocation | null = null;

  /** @param academicYearFrom год начала учебного года (2026 для 2026–2027) или null */
  constructor(
    private readonly format: GridFormat,
    private readonly diagnostics: DiagnosticsCollector,
    private readonly academicYearFrom: number | null,
  ) {
    this.textParser = format.lessonTextParser;
  }

  /** @returns разобранный лист или null, если в шапке нет столбца времени или групп (это записано в диагностику) */
  parse(sheet: SheetView, labelRow: number): SheetResult | null {
    this.sheet = sheet;
    this.slotsWithDerivedEnd = 0;
    this.firstDerivedEnd = null;
    this.hiddenGroups = [];
    this.hiddenCells = 0;
    this.firstHiddenCell = null;

    const header = this.locateHeader(labelRow);
    if (header === null) return null;
    const groups = this.readGroups(header);
    this.allGroups = groups;
    if (groups.length === 0) {
      this.diagnostics.warn('SHEET_NO_GROUPS', 'No group codes found right of the time column', this.cellLocation(header.groupRow, header.timeCol), null);
      return null;
    }

    const entries: ParsedEntry[] = [];
    for (const block of this.findDayBlocks(header)) {
      const day = DAYS[this.normalizeDay(this.text(block[0], header.dayCol))];
      const slots = this.findSlots(block[0], block[1], header.timeCol);
      for (const slot of slots) {
        for (const group of groups) this.parseGroupInSlot(day, slot, group, entries);
      }
      this.reportTextOutsideSlots(block, slots, groups);
      this.countHiddenCells(block, header);
    }
    if (this.hiddenGroups.length > 0) {
      this.diagnostics.warn(
        'HIDDEN_GROUPS_IGNORED',
        `${this.hiddenGroups.length} group(s) stand in columns hidden in Excel and were ignored: ${this.hiddenGroups.join(', ')}`,
        this.cellLocation(header.groupRow, header.timeCol),
        null,
      );
    }
    if (this.hiddenCells > 0) {
      this.diagnostics.warn(
        'HIDDEN_CELLS_IGNORED',
        `${this.hiddenCells} non-empty cell(s) in hidden columns or rows were ignored (Excel does not show them)`,
        this.firstHiddenCell,
        null,
      );
    }
    if (this.slotsWithDerivedEnd > 0) {
      this.diagnostics.warn(
        'SLOT_END_DERIVED',
        `${this.slotsWithDerivedEnd} time slot(s) state only the start time; the end was set to start + ${LESSON_MINUTES} minutes (the length of every explicit interval in the files)`,
        this.firstDerivedEnd,
        null,
      );
    }
    return { groups: this.resolveCourses(header, groups), entries };
  }

  // ---- курсы ------------------------------------------------------------------------------------------------

  /**
   * Курс каждой группы, как его называет лист:
   * 1) надпись над строкой групп, называющая ровно один курс и лежащая над первым столбцом группы (в файле СПО 2–4 курсов
   *    объединённые полосы «2 КУРС» / «3 КУРС» / «4 КУРС»);
   * 2) иначе лист целиком, если его имя и надписи над группами называют ровно один курс («1 курс», «учебных занятий 1 курса»);
   * 3) лист, называющий несколько курсов («1, 2 и 5 курсов»: магистры и 5 курс), не говорит, какая группа к какому курсу относится,
   *    поэтому решает год набора в коде группы, НО только если этот курс назван на листе;
   * 4) иначе неизвестен, записано в диагностику. Курс, выведенный по коду, записывается одним предупреждением на лист.
   */
  private resolveCourses(header: Header, groups: GroupColumns[]): ParsedGroup[] {
    const mentions = this.courseMentionsAbove(header.groupRow);
    const sheetCourseSet = new Set<number>(coursesIn(this.sheet.name));
    mentions.forEach((m) => m.courses.forEach((c) => sheetCourseSet.add(c)));
    const sheetCourses = [...sheetCourseSet].sort((a, b) => a - b);
    const sheetCourse = sheetCourses.length === 1 ? sheetCourses[0] : null;

    const result: ParsedGroup[] = [];
    const unknown: string[] = [];
    const derived: string[] = [];
    for (const group of groups) {
      const fromBandsSet = new Set<number>();
      mentions.filter((m) => namesOneCourseAt(m, firstCol(group))).forEach((m) => m.courses.forEach((c) => fromBandsSet.add(c)));
      const fromBands = [...fromBandsSet];
      let course: number | null = fromBands.length === 1 ? fromBands[0] : fromBands.length === 0 ? sheetCourse : null;
      if (course === null && fromBands.length === 0 && sheetCourses.length > 1 && this.academicYearFrom !== null) {
        const fromCode = courseOf(group.code, this.academicYearFrom);
        if (fromCode !== null && sheetCourses.includes(fromCode)) {
          course = fromCode;
          derived.push(`${group.code}=${fromCode}`);
        }
      }
      if (course === null) unknown.push(group.code);
      result.push({ code: group.code, course });
    }
    const where = this.cellLocation(header.groupRow, header.timeCol);
    if (derived.length > 0) {
      this.diagnostics.warn(
        'COURSE_FROM_GROUP_CODE',
        `The sheet names the courses [${sheetCourses.join(', ')}] without saying which group is which; the course was taken from the admission year in the group code (academic year ${this.academicYearFrom}-${(this.academicYearFrom as number) + 1}): ${derived.join(', ')}`,
        where,
        null,
      );
    }
    if (unknown.length > 0) {
      this.diagnostics.warn(
        'GROUP_COURSE_UNKNOWN',
        `The sheet does not state the course of group(s) ${unknown.join(', ')} unambiguously; their course is left empty`,
        where,
        null,
      );
    }
    return result;
  }

  private courseMentionsAbove(groupRow: number): CourseMention[] {
    const mentions: CourseMention[] = [];
    for (let r = this.sheet.firstRow; r < groupRow; r++) {
      for (const c of this.sheet.cellColumns(r)) {
        const courses = coursesIn(this.sheet.text(r, c));
        if (courses.length === 0) continue;
        const range = this.sheet.mergedAt(r, c);
        if (range !== null && !isAnchor(range, r, c)) continue;
        mentions.push({ courses, firstCol: range ? range.firstCol : c, lastCol: range ? range.lastCol : c });
      }
    }
    return mentions;
  }

  // ---- структура --------------------------------------------------------------------------------------------

  /**
   * Подписи «Дни» и времени могут быть объединены на несколько строк (СПО), а коды групп лежат строкой ниже. Строка групп это
   * строка среди занятых подписями с наибольшим числом заполненных ячеек правее столбца времени (заголовок вида «10 МЕСЯЦЕВ»
   * над кодами содержит одну ячейку, у кодов их много); занятия начинаются ниже всего, что занимает шапка.
   */
  private locateHeader(labelRow: number): Header | null {
    const dayCol = this.findLabel(labelRow, DAYS_LABEL);
    const timeCol = this.findLabel(labelRow, TIME_LABEL);
    if (timeCol < 0) {
      this.diagnostics.warn('SHEET_NO_TIME_COLUMN', 'Header row has no time column label (Часы / время)', this.cellLocation(labelRow, dayCol), null);
      return null;
    }
    const labelLastRow = Math.max(this.lastRowOf(labelRow, dayCol), this.lastRowOf(labelRow, timeCol));
    let groupRow = labelRow;
    let mostFilled = 0;
    for (let r = labelRow; r <= labelLastRow; r++) {
      const filled = this.filledCellsRightOf(r, timeCol);
      if (filled > mostFilled) {
        mostFilled = filled;
        groupRow = r;
      }
    }
    let firstDataRow = labelLastRow + 1;
    for (const c of this.sheet.cellColumns(groupRow)) {
      if (c > timeCol && !isBlank(this.sheet.text(groupRow, c))) {
        firstDataRow = Math.max(firstDataRow, this.lastRowOf(groupRow, c) + 1);
      }
    }
    return { labelRow, dayCol, timeCol, groupRow, firstDataRow };
  }

  private lastRowOf(row: number, col: number): number {
    const range = this.sheet.mergedAt(row, col);
    return range ? range.lastRow : row;
  }

  private filledCellsRightOf(row: number, col: number): number {
    let count = 0;
    for (const c of this.sheet.cellColumns(row)) {
      if (c > col && this.isColumnVisible(c) && !isBlank(this.sheet.text(row, c))) count++;
    }
    return count;
  }

  private isColumnVisible(col: number): boolean {
    return !this.sheet.isColumnHidden(col);
  }

  /** Ближайший видимый столбец строго правее col. */
  private nextVisibleColumn(col: number): number {
    let next = col + 1;
    while (!this.isColumnVisible(next)) next++;
    return next;
  }

  /**
   * Считает (и запоминает первую) непустые ячейки в скрытых столбцах или строках блока дня. Они не часть расписания, но молча
   * терять данные нельзя, поэтому количество записывается в диагностику.
   */
  private countHiddenCells(block: [number, number], header: Header): void {
    for (let r = block[0]; r <= block[1]; r++) {
      const rowHidden = this.sheet.isRowHidden(r);
      for (const c of this.sheet.cellColumns(r)) {
        if (c <= header.timeCol || (!rowHidden && this.isColumnVisible(c)) || isBlank(this.sheet.text(r, c))) continue;
        const range = this.sheet.mergedAt(r, c);
        if (range !== null && !isAnchor(range, r, c)) continue;
        this.hiddenCells++;
        if (this.firstHiddenCell === null) this.firstHiddenCell = this.location(range, r, c);
      }
    }
  }

  private findLabel(row: number, label: RegExp): number {
    for (const c of this.sheet.cellColumns(row)) {
      if (label.test(this.sheet.text(row, c))) return c;
    }
    return -1;
  }

  private readGroups(header: Header): GroupColumns[] {
    const groups: GroupColumns[] = [];
    for (const col of this.sheet.cellColumns(header.groupRow)) {
      const code = this.format.normalizeGroupCode(this.sheet.text(header.groupRow, col));
      if (col <= header.timeCol || code === '') continue;
      const range = this.sheet.mergedAt(header.groupRow, col);
      const last = range ? range.lastCol : col;
      const visible: number[] = [];
      for (let c = col; c <= last; c++) {
        if (this.isColumnVisible(c)) visible.push(c);
      }
      if (visible.length === 0) this.hiddenGroups.push(code);
      else groups.push({ code, columns: visible });
    }
    return groups;
  }

  /** Строки [первая, последняя] каждого распознанного блока дня; неизвестные названия дней записываются и пропускаются. */
  private findDayBlocks(header: Header): Array<[number, number]> {
    const blocks: Array<[number, number]> = [];
    for (let r = header.firstDataRow; r <= this.sheet.lastRow; r++) {
      const name = this.text(r, header.dayCol);
      if (isBlank(name)) continue;
      const range = this.sheet.mergedAt(r, header.dayCol);
      const end = range ? range.lastRow : r;
      if (this.normalizeDay(name) in DAYS) {
        blocks.push([r, end]);
      } else {
        this.diagnostics.warn('UNKNOWN_DAY', `Unrecognised day name '${name.trim()}'; its rows were not read`, this.cellLocation(r, header.dayCol), name);
      }
      r = end;
    }
    return blocks;
  }

  private findSlots(firstRow: number, lastRow: number, timeCol: number): TimeSlot[] {
    const slots: TimeSlot[] = [];
    for (let r = firstRow; r <= lastRow; r++) {
      const time = this.text(r, timeCol);
      if (isBlank(time) || this.sheet.isRowHidden(r)) continue;
      const range = this.sheet.mergedAt(r, timeCol);
      const end = range ? range.lastRow : r;
      const slot = this.readSlot(time, r, end);
      if (slot !== null) slots.push(slot);
      else this.diagnostics.warn('UNPARSEABLE_TIME', `Cannot read time interval '${time.trim()}'; its rows were not read`, this.cellLocation(r, timeCol), time);
      r = end;
    }
    return slots;
  }

  /** Читает «08.00-09.35», «8.00-9.35» или только начало («1) 8 00», конец = начало + одна пара). */
  private readSlot(time: string, firstRow: number, lastRow: number): TimeSlot | null {
    const range = TIME_RANGE.exec(time);
    if (range) {
      return {
        firstRow,
        lastRow,
        startMin: minutesOf(Number(range[1]), Number(range[2])),
        endMin: minutesOf(Number(range[3]), Number(range[4])),
      };
    }
    const start = TIME_START.exec(time);
    if (start) {
      const from = minutesOf(Number(start[1]), Number(start[2]));
      this.slotsWithDerivedEnd++;
      if (this.firstDerivedEnd === null) this.firstDerivedEnd = { sheet: this.sheet.name, range: cellRef(firstRow, 0) };
      return { firstRow, lastRow, startMin: from, endMin: (from + LESSON_MINUTES) % 1440 };
    }
    return null;
  }

  // ---- занятия ----------------------------------------------------------------------------------------------

  /**
   * Текст занятия в строках блока дня, не относящихся ни к одному слоту (ячейка времени сдвинута относительно занятий), нельзя
   * поставить во времени. Он записывается как пропущенное занятие, а не игнорируется молча.
   */
  private reportTextOutsideSlots(block: [number, number], slots: TimeSlot[], groups: GroupColumns[]): void {
    for (let r = block[0]; r <= block[1]; r++) {
      if (this.sheet.isRowHidden(r) || slots.some((sl) => r >= sl.firstRow && r <= sl.lastRow)) continue;
      for (const group of groups) {
        for (const c of group.columns) {
          const text = this.text(r, c);
          const range = this.sheet.mergedAt(r, c);
          if (isBlank(text) || (range !== null && !isAnchor(range, r, c))) continue;
          this.diagnostics.entrySkipped('TEXT_OUTSIDE_TIME_SLOT', 'Lesson text lies in rows that belong to no time slot, so its time is unknown', this.location(range, r, c), text);
        }
      }
    }
  }

  private parseGroupInSlot(day: number, slot: TimeSlot, group: GroupColumns, out: ParsedEntry[]): void {
    for (let r = slot.firstRow; r <= slot.lastRow; r++) {
      if (this.sheet.isRowHidden(r)) continue;
      for (const c of group.columns) {
        const text = this.text(r, c);
        const range = this.sheet.mergedAt(r, c);
        if (range !== null && !isAnchor(range, r, c)) {
          if (!isBlank(text)) {
            this.diagnostics.warn('HIDDEN_TEXT_IN_MERGED_CELL', 'Text in a non-anchor cell of a merged range is ignored by Excel and was not read', this.cellLocation(r, c), text);
          }
          continue;
        }
        if (isBlank(text)) continue;
        this.parseLesson(day, slot, group, r, c, range, text, out);
      }
    }
  }

  private parseLesson(day: number, slot: TimeSlot, group: GroupColumns, row: number, col: number, range: MergedRange | null, text: string, out: ParsedEntry[]): void {
    const result = this.textParser.parse(text);
    const where = this.location(range, row, col);
    if (result.kind === 'rejected') {
      if (result.code !== 'ZERO_PLACEHOLDER') this.diagnostics.entrySkipped(result.code, result.message, where, text);
      return;
    }
    const content = result;

    const r1 = range ? range.firstRow : row;
    const r2 = range ? range.lastRow : row;
    // Считается только видимая часть диапазона: занятие, растянутое на скрытые столбцы своей группы (или столбцы, не
    // принадлежащие ни одной видимой группе), это занятие для видимых столбцов, которые оно накрывает.
    const rawC1 = range ? range.firstCol : col;
    const rawC2 = range ? range.lastCol : col;
    let c1 = rawC1;
    while (c1 <= rawC2 && !this.isColumnVisible(c1)) c1++;
    let c2 = rawC2;
    while (c2 >= rawC1 && !this.isColumnVisible(c2)) c2--;
    if (c1 > c2) return; // целиком в скрытых столбцах: посчитано в countHiddenCells

    if (r1 < slot.firstRow || r2 > slot.lastRow || c1 < firstCol(group)) {
      this.diagnostics.entrySkipped('MERGE_CROSSES_BOUNDARY', 'Merged range leaves its time slot or group columns', where, text);
      return;
    }
    const week = this.weekType(slot, r1, r2);
    if (week === null) {
      this.diagnostics.entrySkipped(
        'UNEXPECTED_SLOT_HEIGHT',
        `Time slot spans ${slotHeight(slot)} rows instead of ${EXPECTED_SLOT_HEIGHT}; numerator/denominator cannot be told apart`,
        where,
        text,
      );
      return;
    }

    // Занятие, общее для нескольких групп (поток), это одна ячейка, объединённая на соседние группы. Оно повторяется для каждой.
    // Если диапазон заканчивается (или начинается) внутри группы, эта группа накрыта частично: политика формата решает,
    // отвергнуть занятие или показать целой группе с предупреждением.
    if (c2 > lastCol(group)) {
      const spans = this.groupsIntersecting(c1, c2);
      const anyPartial = spans.some((sp) => !sp.full);
      if (spans.length === 0 || (anyPartial && this.format.partialSpanPolicy === 'SKIP_WITH_ERROR')) {
        this.diagnostics.entrySkipped('MERGE_CROSSES_BOUNDARY', 'Merged range cuts through a group instead of covering whole groups', where, text);
        return;
      }
      for (const span of spans) {
        if (!span.full) {
          this.diagnostics.warn(
            'PARTIAL_SPAN_AS_WHOLE_GROUP',
            `Shared lesson covers only part of group ${span.group.code} and names no subgroup there; shown for the whole group`,
            where,
            text,
          );
        }
        this.emit(day, slot, span.group, week, content.subgroup, content, text, where, out);
      }
      return;
    }

    const subgroup = this.resolveSubgroup(group, c1, c2, content, where, text);
    if (subgroup === null && !this.isWholeGroup(group, c1, c2) && c1 !== c2 && content.subgroup === null && this.format.partialSpanPolicy === 'SKIP_WITH_ERROR') {
      this.diagnostics.entrySkipped('PARTIAL_GROUP_SPAN', `Lesson covers some but not all of ${group.columns.length} subgroup columns`, where, text);
      return;
    }
    this.emit(day, slot, group, week, subgroup, content, text, where, out);
  }

  /**
   * Подгруппа занятия внутри одной группы. Подгруппа, названная в тексте, побеждает (СПО пишет «1 под.»); иначе решает столбец:
   * вся ширина это вся группа, один столбец это эта подгруппа. Занятие над частью нескольких столбцов подчиняется политике формата.
   */
  private resolveSubgroup(group: GroupColumns, c1: number, c2: number, content: CellContent, where: SourceLocation, raw: string): number | null {
    const fromText = content.subgroup;
    const whole = this.isWholeGroup(group, c1, c2);
    const fromColumn = !whole && c1 === c2 ? subgroupIndex(group, c1) : null;
    if (fromText !== null) {
      if (fromColumn !== null && fromColumn !== fromText) {
        this.diagnostics.warn('SUBGROUP_MISMATCH', `Text says subgroup ${fromText} but the column says subgroup ${fromColumn}; the text is used`, where, raw);
      }
      return fromText;
    }
    if (whole) return null;
    if (fromColumn !== null) return fromColumn;
    if (this.format.partialSpanPolicy === 'WHOLE_GROUP_WITH_WARNING') {
      this.diagnostics.warn(
        'PARTIAL_SPAN_AS_WHOLE_GROUP',
        `Lesson covers some but not all of the ${group.columns.length} columns of the group and names no subgroup; shown for the whole group`,
        where,
        raw,
      );
    }
    return null;
  }

  private isWholeGroup(group: GroupColumns, c1: number, c2: number): boolean {
    return c1 === firstCol(group) && c2 === lastCol(group);
  }

  /**
   * Группы, которых касается диапазон столбцов c1..c2, по порядку. Пусто, если диапазон не лежит на соседних группах от края до
   * края (разрыв или столбцы, не принадлежащие ни одной группе).
   */
  private groupsIntersecting(c1: number, c2: number): GroupSpan[] {
    const spans: GroupSpan[] = [];
    let previousLast = -1;
    for (const g of this.allGroups) {
      if (lastCol(g) < c1 || firstCol(g) > c2) continue;
      if (previousLast !== -1 && firstCol(g) !== this.nextVisibleColumn(previousLast)) return [];
      if (previousLast === -1 && firstCol(g) > c1) return [];
      spans.push({ group: g, full: firstCol(g) >= c1 && lastCol(g) <= c2 });
      previousLast = lastCol(g);
    }
    return spans.length > 0 && previousLast >= c2 ? spans : [];
  }

  private emit(day: number, slot: TimeSlot, group: GroupColumns, week: WeekType, subgroup: number | null, content: CellContent, text: string, where: SourceLocation, out: ParsedEntry[]): void {
    const entry: ParsedEntry = {
      groupCode: group.code,
      dayOfWeek: day,
      date: null,
      startTime: formatTime(slot.startMin),
      endTime: formatTime(slot.endMin),
      weekType: week,
      subgroup,
      subject: content.subject,
      lessonType: content.lessonType as LessonType,
      teacher: content.teacher,
      room: content.room,
      rawText: text,
      sheet: where.sheet,
      range: where.range,
    };
    const problem = validateEntry(entry, slot);
    if (problem !== null) {
      this.diagnostics.entrySkipped('INVALID_ENTRY', problem, where, text);
      return;
    }
    out.push(entry);
    this.diagnostics.entryRecognized();
    content.warnings.forEach((w) => this.diagnostics.warn(w.code, w.message, where, text));
    this.checkSubgroupMarker(content.subject, subgroup, where, text);
  }

  /** Верхняя строка = числитель, нижняя = знаменатель, обе = каждая неделя. null для слота необычной высоты. */
  private weekType(slot: TimeSlot, r1: number, r2: number): WeekType | null {
    if (slotHeight(slot) !== EXPECTED_SLOT_HEIGHT) return null;
    const top = r1 === slot.firstRow;
    const bottom = r2 === slot.lastRow;
    if (top && bottom) return 'EVERY_WEEK';
    return top ? 'NUMERATOR' : 'DENOMINATOR';
  }

  /** В названии иногда повторена подгруппа («... - 2 п/г»): она должна совпадать с геометрией. */
  private checkSubgroupMarker(subject: string, subgroup: number | null, where: SourceLocation, raw: string): void {
    const m = SUBGROUP_IN_TEXT.exec(subject);
    if (m && (subgroup === null || Number(m[1]) !== subgroup)) {
      this.diagnostics.warn('SUBGROUP_MISMATCH', `Text says subgroup ${m[1]} but the cell position says ${subgroup === null ? 'whole group' : `subgroup ${subgroup}`}`, where, raw);
    }
  }

  // ---- помощники --------------------------------------------------------------------------------------------

  private text(row: number, col: number): string {
    return this.sheet.text(row, col);
  }

  private normalizeDay(name: string): string {
    return name.trim().toUpperCase();
  }

  private cellLocation(row: number, col: number): SourceLocation {
    return { sheet: this.sheet.name, range: cellRef(row, Math.max(col, 0)) };
  }

  private location(range: MergedRange | null, row: number, col: number): SourceLocation {
    return range ? { sheet: this.sheet.name, range: rangeRef(range) } : this.cellLocation(row, col);
  }
}

/** Те же проверки, что в конструкторе Java-записи ParsedEntry; текст ошибки попадает в диагностику. */
export function validateEntry(entry: ParsedEntry, slot: { startMin: number; endMin: number } | null): string | null {
  if (slot !== null && !(slot.startMin < slot.endMin)) {
    return `startTime must be before endTime: ${entry.startTime} - ${entry.endTime}`;
  }
  if (entry.subgroup !== null && entry.subgroup < 1) return `subgroup must be >= 1 or null, was ${entry.subgroup}`;
  if (isBlank(entry.subject)) return 'subject must not be blank';
  return null;
}
