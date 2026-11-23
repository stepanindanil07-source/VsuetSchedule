import { cellRef, isAnchor, rangeRef } from '../../excel/cellRef';
import type { MergedRange, SheetView } from '../../excel/types';
import type { ParsedEntry, ParsedGroup } from '../../model/types';
import type { DiagnosticsCollector, SourceLocation } from '../../parser/diagnostics';
import { collapseSpaces, formatTime, isBlank, L, minutesOf } from '../../util/text';
import { validateEntry } from '../grid/sheetParser';
import { parseZaoLesson } from './lessonText';

/**
 * Один лист заочного (zaochnoe) расписания. В отличие от недельной сетки остальных факультетов:
 *
 *   столбец A   календарный день, объединён на свои строки:  «ПНД \n12/10»  (день недели + день/месяц, без года)
 *   столбец B   слоты времени, каждый объединён на ДВЕ строки: «08.00-09.35»
 *   у группы    три столбца (средний скрыт): [текст] [скрыт] [аудитория]
 *                 строка 1 слота:  «тип. предмет»    аудитория
 *                 строка 2 слота:  преподаватель      (вторая аудитория)
 *
 * Числителя и знаменателя нет, ничего не объединено между группами: каждая группа повторяет свои занятия сама. Год в «12/10» в файле
 * не указан, он берётся у привязанного семестра, а день недели, написанный рядом с датой, проверяется по нему: неверная привязка
 * не пройдёт незамеченной. Скрытые столбцы и строки не читаются. Текст, не попавший ни в одну из четырёх позиций, не теряется молча:
 * внутри группы это пропущенное занятие, вне всех групп это один счётчик в предупреждении.
 * Перенос Java-класса ZaoSheetParser.
 */

const DAYS_LABEL = /^\s*дни\s*$/i;
const TIME_LABEL = /^\s*часы\s*$/i;
const DAY_AND_DATE = new RegExp(`^\\s*([${L}]{3})\\.?\\s+(\\d{1,2})\\s*/\\s*(\\d{1,2})\\s*$`);
const TIME_RANGE = /(\d{1,2})[.: ](\d{2})\s*[-–]\s*(\d{1,2})[.: ](\d{2})/;
const HEADER_SEARCH_LIMIT = 100;
const SLOT_HEIGHT = 2;

/** День недели по ISO (1 = понедельник) и по названию для сообщений. */
const WEEKDAYS: Record<string, number> = { ПНД: 1, ВТР: 2, СРД: 3, ЧТВ: 4, ПТН: 5, СБТ: 6, ВСК: 7 };
const WEEKDAY_NAMES = ['', 'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];

interface GroupColumns {
  code: string;
  textCol: number;
  /** -1, если у группы один видимый столбец. */
  roomCol: number;
  columns: number[];
}

interface Slot {
  firstRow: number;
  startMin: number;
  endMin: number;
}

export interface ZaoSheetResult {
  groups: ParsedGroup[];
  entries: ParsedEntry[];
}

/** Номер строки (с нуля) со словом «Дни» или -1. */
export function findZaoHeaderRow(sheet: SheetView): number {
  const limit = Math.min(sheet.lastRow, HEADER_SEARCH_LIMIT);
  for (let r = sheet.firstRow; r <= limit; r++) {
    for (const c of sheet.cellColumns(r)) {
      if (DAYS_LABEL.test(sheet.text(r, c))) return r;
    }
  }
  return -1;
}

/** Дата «день/месяц» первого года, начиная с которого она не раньше начала семестра; null для несуществующей даты. */
function resolveDate(day: number, month: number, periodStart: string): string | null {
  const startYear = Number(periodStart.slice(0, 4));
  const valid = (y: number, m: number, d: number): boolean => {
    const t = new Date(Date.UTC(y, m - 1, d));
    return t.getUTCFullYear() === y && t.getUTCMonth() === m - 1 && t.getUTCDate() === d;
  };
  if (!valid(startYear, month, day)) return null;
  let year = startYear;
  let d = day;
  let iso = iso3(year, month, d);
  if (iso < periodStart) {
    year += 1;
    // 29 февраля в невисокосном году превращается в 28-е, как у LocalDate.plusYears
    if (!valid(year, month, d)) d = 28;
    iso = iso3(year, month, d);
  }
  return iso;
}

const iso3 = (y: number, m: number, d: number): string => `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`;

function isoWeekday(iso: string): number {
  const d = new Date(Date.UTC(Number(iso.slice(0, 4)), Number(iso.slice(5, 7)) - 1, Number(iso.slice(8, 10)))).getUTCDay();
  return d === 0 ? 7 : d;
}

export class ZaoSheetParser {
  private sheet!: SheetView;
  private readonly consumed = new Set<number>();
  private hiddenCells = 0;
  private firstHiddenCell: SourceLocation | null = null;
  private outsideCells = 0;
  private firstOutsideCell: SourceLocation | null = null;

  constructor(
    private readonly diagnostics: DiagnosticsCollector,
    private readonly periodStart: string,
  ) {}

  /** @returns разобранный лист или null, если в шапке нет столбца времени или видимых групп (записано в диагностику) */
  parse(sheet: SheetView, headerRow: number): ZaoSheetResult | null {
    this.sheet = sheet;
    const dayCol = this.findLabel(headerRow, DAYS_LABEL);
    const timeCol = this.findLabel(headerRow, TIME_LABEL);
    if (timeCol < 0) {
      this.diagnostics.warn('SHEET_NO_TIME_COLUMN', 'Header row has no time column label (Часы)', this.cellLocation(headerRow, dayCol), null);
      return null;
    }
    const hiddenGroups: string[] = [];
    const groups = this.readGroups(headerRow, timeCol, hiddenGroups);
    if (groups.length === 0) {
      this.diagnostics.warn('SHEET_NO_GROUPS', 'No visible group codes found right of the time column', this.cellLocation(headerRow, timeCol), null);
      return null;
    }
    const groupColumns = new Set<number>();
    groups.forEach((g) => g.columns.forEach((c) => groupColumns.add(c)));

    const entries: ParsedEntry[] = [];
    for (const block of this.findDayBlocks(headerRow, dayCol)) {
      this.parseDay(block, dayCol, timeCol, groups, entries);
      this.reportUnplacedCells(block, timeCol, groupColumns);
    }
    if (hiddenGroups.length > 0) {
      this.diagnostics.warn(
        'HIDDEN_GROUPS_IGNORED',
        `${hiddenGroups.length} group(s) stand in columns hidden in Excel and were ignored: ${hiddenGroups.join(', ')}`,
        this.cellLocation(headerRow, timeCol),
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
    if (this.outsideCells > 0) {
      this.diagnostics.warn(
        'CELLS_OUTSIDE_GROUPS_IGNORED',
        `${this.outsideCells} non-empty cell(s) lie outside every group's columns and were ignored (leftovers such as a room without a lesson)`,
        this.firstOutsideCell,
        null,
      );
    }
    return { groups: groups.map((g) => ({ code: g.code, course: null })), entries };
  }

  // ---- структура --------------------------------------------------------------------------------------------

  private findLabel(row: number, label: RegExp): number {
    for (const c of this.sheet.cellColumns(row)) {
      if (label.test(this.sheet.text(row, c))) return c;
    }
    return -1;
  }

  private readGroups(headerRow: number, timeCol: number, hiddenGroups: string[]): GroupColumns[] {
    const groups: GroupColumns[] = [];
    for (const col of this.sheet.cellColumns(headerRow)) {
      const code = collapseSpaces(this.sheet.text(headerRow, col)).trim();
      if (col <= timeCol || code === '') continue;
      const range = this.sheet.mergedAt(headerRow, col);
      const last = range ? range.lastCol : col;
      const visible: number[] = [];
      for (let c = col; c <= last; c++) {
        if (!this.sheet.isColumnHidden(c)) visible.push(c);
      }
      if (visible.length === 0) hiddenGroups.push(code);
      else groups.push({ code, textCol: visible[0], roomCol: visible.length > 1 ? visible[visible.length - 1] : -1, columns: visible });
    }
    return groups;
  }

  /** Строки [первая, последняя] каждого блока дня (объединённая ячейка в столбце дней). */
  private findDayBlocks(headerRow: number, dayCol: number): Array<[number, number]> {
    const blocks: Array<[number, number]> = [];
    for (let r = headerRow + 1; r <= this.sheet.lastRow; r++) {
      if (isBlank(this.sheet.text(r, dayCol))) continue;
      const range = this.sheet.mergedAt(r, dayCol);
      const end = range ? range.lastRow : r;
      blocks.push([r, end]);
      r = end;
    }
    return blocks;
  }

  // ---- один день --------------------------------------------------------------------------------------------

  private parseDay(block: [number, number], dayCol: number, timeCol: number, groups: GroupColumns[], out: ParsedEntry[]): void {
    const date = this.readDate(this.sheet.text(block[0], dayCol), block[0], dayCol);
    if (date === null) return; // записано; ячейки блока попадут в «неразмещённые»
    for (const slot of this.findSlots(block, timeCol)) {
      for (const group of groups) this.parseLesson(date, slot, group, out);
    }
  }

  /**
   * «ПНД \n12/10» → дата. Год это первый, при котором дата не раньше начала семестра; написанный рядом день недели обязан
   * совпасть, иначе весь день отклоняется (неверная привязка семестра или опечатка: угадать нельзя).
   */
  private readDate(label: string, row: number, col: number): string | null {
    const m = DAY_AND_DATE.exec(label);
    const written = m ? WEEKDAYS[m[1].toUpperCase()] : undefined;
    const shown = label.trim().replace(/\n/g, ' ');
    if (!m || written === undefined) {
      this.diagnostics.warn('UNKNOWN_DAY', `Unrecognised day label '${shown}' (expected e.g. 'ПНД 12/10'); its rows were not read`, this.cellLocation(row, col), label);
      return null;
    }
    const date = resolveDate(Number(m[2]), Number(m[3]), this.periodStart);
    if (date === null) {
      this.diagnostics.warn('INVALID_DATE', `'${shown}' is not a calendar date; its rows were not read`, this.cellLocation(row, col), label);
      return null;
    }
    const actual = isoWeekday(date);
    if (actual !== written) {
      this.diagnostics.entrySkipped(
        'DAY_WEEKDAY_MISMATCH',
        `The label says ${WEEKDAY_NAMES[written]} but ${date} is a ${WEEKDAY_NAMES[actual]} (is the source pinned to the right semester?); the day was not read`,
        this.cellLocation(row, col),
        label,
      );
      return null;
    }
    return date;
  }

  private findSlots(block: [number, number], timeCol: number): Slot[] {
    const slots: Slot[] = [];
    for (let r = block[0]; r <= block[1]; r++) {
      const time = this.sheet.text(r, timeCol);
      if (isBlank(time) || this.sheet.isRowHidden(r)) continue;
      const range = this.sheet.mergedAt(r, timeCol);
      const end = range ? range.lastRow : r;
      const m = TIME_RANGE.exec(time);
      if (!m) {
        this.diagnostics.warn('UNPARSEABLE_TIME', `Cannot read time interval '${time.trim()}'; its rows were not read`, this.cellLocation(r, timeCol), time);
      } else if (end - r + 1 !== SLOT_HEIGHT) {
        this.diagnostics.warn(
          'UNEXPECTED_SLOT_HEIGHT',
          `Time slot spans ${end - r + 1} rows instead of ${SLOT_HEIGHT} (subject row + teacher row); its rows were not read`,
          this.cellLocation(r, timeCol),
          time,
        );
      } else {
        slots.push({ firstRow: r, startMin: minutesOf(Number(m[1]), Number(m[2])), endMin: minutesOf(Number(m[3]), Number(m[4])) });
      }
      r = end;
    }
    return slots;
  }

  private parseLesson(date: string, slot: Slot, group: GroupColumns, out: ParsedEntry[]): void {
    const top = slot.firstRow;
    const bottom = top + 1;
    if (this.sheet.isRowHidden(top) || this.sheet.isRowHidden(bottom)) return; // посчитано как скрытые ячейки в reportUnplacedCells
    const subject = this.sheet.text(top, group.textCol);
    const teacher = this.sheet.text(bottom, group.textCol);
    const room1 = group.roomCol < 0 ? '' : this.sheet.text(top, group.roomCol);
    const room2 = group.roomCol < 0 ? '' : this.sheet.text(bottom, group.roomCol);
    this.consume(top, group.textCol);
    this.consume(bottom, group.textCol);
    if (group.roomCol >= 0) {
      this.consume(top, group.roomCol);
      this.consume(bottom, group.roomCol);
    }
    if (isBlank(subject) && isBlank(teacher) && isBlank(room1) && isBlank(room2)) return;

    const lastCol = group.roomCol >= 0 ? group.roomCol : group.textCol;
    const where: SourceLocation = { sheet: this.sheet.name, range: rangeRef({ firstRow: top, lastRow: bottom, firstCol: group.textCol, lastCol }) };
    const raw = [subject, teacher, room1, room2].map((s) => s.trim()).filter((s) => s !== '').join('\n');

    const parsed = parseZaoLesson(subject, teacher, room1, room2);
    if (parsed.kind === 'rejected') {
      this.diagnostics.entrySkipped(parsed.code, parsed.message, where, raw);
      return;
    }
    const entry: ParsedEntry = {
      groupCode: group.code,
      dayOfWeek: isoWeekday(date),
      date,
      startTime: formatTime(slot.startMin),
      endTime: formatTime(slot.endMin),
      weekType: 'EVERY_WEEK',
      subgroup: parsed.subgroup,
      subject: parsed.subject,
      lessonType: parsed.type,
      teacher: parsed.teacher,
      room: parsed.room,
      rawText: raw,
      sheet: where.sheet,
      range: where.range,
    };
    const problem = validateEntry(entry, slot);
    if (problem !== null) {
      this.diagnostics.entrySkipped('INVALID_ENTRY', problem, where, raw);
      return;
    }
    out.push(entry);
    this.diagnostics.entryRecognized();
    parsed.warnings.forEach((w) => this.diagnostics.warn(w.code, w.message, where, raw));
  }

  // ---- ничего не молча --------------------------------------------------------------------------------------

  private consume(row: number, col: number): void {
    this.consumed.add(row * 16384 + col);
  }

  /**
   * Каждая непустая ячейка блока, не прочитанная как часть занятия, учтена: скрытые считаются, текст в столбцах группы становится
   * пропущенным занятием (слот необычной формы, текст рядом со скрытым средним столбцом...), текст вне всех групп считается одним
   * предупреждением.
   */
  private reportUnplacedCells(block: [number, number], timeCol: number, groupColumns: Set<number>): void {
    for (let r = block[0]; r <= block[1]; r++) {
      const rowHidden = this.sheet.isRowHidden(r);
      for (const c of this.sheet.cellColumns(r)) {
        const text = this.sheet.text(r, c);
        if (c <= timeCol || isBlank(text) || this.consumed.has(r * 16384 + c)) continue;
        const range = this.sheet.mergedAt(r, c);
        if (range !== null && !isAnchor(range, r, c)) continue;
        const where: SourceLocation = range ? { sheet: this.sheet.name, range: rangeRef(range) } : this.cellLocation(r, c);
        if (rowHidden || this.sheet.isColumnHidden(c)) {
          this.hiddenCells++;
          if (this.firstHiddenCell === null) this.firstHiddenCell = where;
        } else if (groupColumns.has(c)) {
          this.diagnostics.entrySkipped('UNPLACED_TEXT', "Text where no lesson is expected: not in a valid time slot or not in a group's text/room column", where, text);
        } else {
          this.outsideCells++;
          if (this.firstOutsideCell === null) this.firstOutsideCell = where;
        }
      }
    }
  }

  private cellLocation(row: number, col: number): SourceLocation {
    return { sheet: this.sheet.name, range: cellRef(row, Math.max(col, 0)) };
  }
}

export type { MergedRange };
