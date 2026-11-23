import { openWorkbook } from '../../excel/sheetjs';
import type { SheetView } from '../../excel/types';
import type { ParsedEntry, ParsedGroup, ParsedPeriod, ParsedSchedule } from '../../model/types';
import { DiagnosticsCollector } from '../../parser/diagnostics';
import { ScheduleParseError, type ParserInput, type ScheduleParser } from '../../parser/types';
import { isBlank } from '../../util/text';
import type { GridFormat } from './format';
import { findHeaderRow, GridSheetParser } from './sheetParser';

const SEMESTER = /(весенн[А-Яа-яЁё]*|осенн[А-Яа-яЁё]*)\s+семестр[А-Яа-яЁё]*\s+(\d{4})\s*[-–]\s*(\d{4})/i;

/**
 * Общая для всех «сеток» ВГУИТ работа на уровне книги: открыть файл (xls или xlsx), разобрать каждый лист расписания,
 * найти семестр, объединить листы и решить, когда файл слишком не похож на ожидаемый, чтобы ему доверять. Конкретный парсер
 * даёт только идентификатор, версию и {@link GridFormat}.
 *
 * Фатально (импорт отклоняется целиком): нечитаемый файл, непустой лист без нужной шапки, семестр, о котором листы спорят,
 * повторяющиеся коды групп, ни одного занятия, а при `periodRequired` ещё и отсутствие семестра. Без названного семестра
 * результат не содержит периода, и источник должен быть привязан к периоду приложением.
 * Перенос Java-класса GridWorkbookParser.
 */
export abstract class GridWorkbookParser implements ScheduleParser {
  protected constructor(
    readonly parserId: string,
    readonly parserVersion: string,
    private readonly format: GridFormat,
    private readonly periodRequired: boolean,
  ) {}

  parse(input: ParserInput): ParsedSchedule {
    const diagnostics = new DiagnosticsCollector();
    let sheets: readonly SheetView[];
    try {
      sheets = openWorkbook(input.bytes).sheets;
    } catch (e) {
      throw this.fail(`Cannot read the file as an Excel workbook: ${(e as Error).message}`, diagnostics, e);
    }

    // Сначала найти листы расписания и семестр: группам листа может понадобиться учебный год (курсы), хотя сам лист
    // семестра не называет (лист магистров часто его не называет).
    const scheduleSheets: Array<{ sheet: SheetView; headerRow: number }> = [];
    const periods: ParsedPeriod[] = [];
    for (const sheet of sheets) {
      const headerRow = findHeaderRow(sheet);
      if (headerRow < 0) {
        if (isEmptySheet(sheet)) {
          diagnostics.warn('EMPTY_SHEET_IGNORED', 'Sheet is empty', { sheet: sheet.name, range: '-' }, null);
          continue;
        }
        throw this.fail(`Sheet '${sheet.name}' has no 'Дни' header: unknown layout`, diagnostics, null);
      }
      const period = this.findPeriod(sheet, headerRow, diagnostics);
      if (period !== null && !periods.some((p) => samePeriod(p, period))) periods.push(period);
      scheduleSheets.push({ sheet, headerRow });
    }
    const academicYear = input.context.academicYearFrom !== null ? input.context.academicYearFrom : periods.length === 1 ? periods[0].yearFrom : null;

    const groups: ParsedGroup[] = [];
    const entries: ParsedEntry[] = [];
    const groupCodes = new Set<string>();
    for (const { sheet, headerRow } of scheduleSheets) {
      const result = new GridSheetParser(this.format, diagnostics, academicYear).parse(sheet, headerRow);
      if (result === null) throw this.fail(`Sheet '${sheet.name}' has an unexpected header structure`, diagnostics, null);
      for (const group of result.groups) {
        if (groupCodes.has(group.code)) throw this.fail(`Group '${group.code}' appears more than once (sheet '${sheet.name}')`, diagnostics, null);
        groupCodes.add(group.code);
      }
      groups.push(...result.groups);
      entries.push(...result.entries);
    }

    if (scheduleSheets.length === 0) throw this.fail('The workbook contains no schedule sheets', diagnostics, null);
    if (periods.length === 0 && this.periodRequired) {
      throw this.fail("Semester (e.g. 'весеннего семестра 2025-2026') not found on any sheet", diagnostics, null);
    }
    if (periods.length > 1) throw this.fail(`Sheets disagree about the semester: ${JSON.stringify(periods)}`, diagnostics, null);
    if (entries.length === 0) throw this.fail('No lessons found in the workbook', diagnostics, null);
    return {
      parserKey: this.parserId,
      period: periods.length === 0 ? null : periods[0],
      groups,
      entries,
      diagnostics: diagnostics.build(this.parserVersion),
    };
  }

  /** Ищет семестр в строках над шапкой; листы без него допустимы (например, лист магистров). */
  private findPeriod(sheet: SheetView, headerRow: number, diagnostics: DiagnosticsCollector): ParsedPeriod | null {
    for (let r = sheet.firstRow; r < headerRow; r++) {
      for (const c of sheet.cellColumns(r)) {
        const m = SEMESTER.exec(sheet.text(r, c));
        if (!m) continue;
        const term = m[1].toLowerCase().startsWith('весенн') ? 'SPRING' : 'AUTUMN';
        const yearFrom = Number(m[2]);
        const yearTo = Number(m[3]);
        if (yearTo !== yearFrom + 1) {
          throw this.fail(`Invalid semester '${m[0]}': Academic year must span two consecutive years: ${yearFrom}-${yearTo}`, diagnostics, null);
        }
        return { yearFrom, yearTo, term };
      }
    }
    return null;
  }

  private fail(message: string, diagnostics: DiagnosticsCollector, cause: unknown): ScheduleParseError {
    return new ScheduleParseError(message, diagnostics.build(this.parserVersion), cause ?? undefined);
  }
}

function samePeriod(a: ParsedPeriod, b: ParsedPeriod): boolean {
  return a.yearFrom === b.yearFrom && a.yearTo === b.yearTo && a.term === b.term;
}

function isEmptySheet(sheet: SheetView): boolean {
  for (let r = sheet.firstRow; r <= sheet.lastRow; r++) {
    for (const c of sheet.cellColumns(r)) {
      if (!isBlank(sheet.text(r, c))) return false;
    }
  }
  return true;
}
