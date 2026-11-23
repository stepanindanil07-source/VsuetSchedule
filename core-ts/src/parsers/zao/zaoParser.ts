import { openWorkbook } from '../../excel/sheetjs';
import type { SheetView } from '../../excel/types';
import type { ParsedEntry, ParsedGroup, ParsedSchedule } from '../../model/types';
import { DiagnosticsCollector } from '../../parser/diagnostics';
import { ScheduleParseError, type ParserInput, type ScheduleParser } from '../../parser/types';
import { isBlank } from '../../util/text';
import { findZaoHeaderRow, ZaoSheetParser } from './sheetParser';

export const ZAO_PARSER_ID = 'zao-xlsx';

/**
 * Парсер расписания заочного обучения (`zao/5_kurs.xlsx`): занятия перечислены по календарным датам, а не по дню недели и
 * чётности недели, поэтому у каждой записи есть дата. Устройство листа описано в {@link ZaoSheetParser}. Не зависит от парсеров
 * недельной сетки: общего с ними только каноническая модель.
 *
 * Файл не называет ни семестр, ни курс и пишет даты без года: источник должен быть привязан к учебному периоду
 * (приложение передаёт его первый день в контексте), курс группам задаёт источник.
 */
export class ZaoExcelParser implements ScheduleParser {
  readonly parserId = ZAO_PARSER_ID;
  readonly parserVersion = '1';

  parse(input: ParserInput): ParsedSchedule {
    const diagnostics = new DiagnosticsCollector();
    const periodStart = input.context.periodStart;
    if (periodStart === null) {
      throw this.fail(
        'The correspondence schedule writes dates without a year; pin the source to an academic period so the year can be derived',
        diagnostics,
        null,
      );
    }
    let sheets: readonly SheetView[];
    try {
      sheets = openWorkbook(input.bytes).sheets;
    } catch (e) {
      throw this.fail(`Cannot read the file as an Excel workbook: ${(e as Error).message}`, diagnostics, e);
    }

    const groups: ParsedGroup[] = [];
    const entries: ParsedEntry[] = [];
    const codes = new Set<string>();
    let parsedSheets = 0;
    for (const sheet of sheets) {
      const headerRow = findZaoHeaderRow(sheet);
      if (headerRow < 0) {
        if (isEmptySheet(sheet)) {
          diagnostics.warn('EMPTY_SHEET_IGNORED', 'Sheet is empty', { sheet: sheet.name, range: '-' }, null);
          continue;
        }
        throw this.fail(`Sheet '${sheet.name}' has no 'Дни' header: unknown layout`, diagnostics, null);
      }
      const result = new ZaoSheetParser(diagnostics, periodStart).parse(sheet, headerRow);
      if (result === null) throw this.fail(`Sheet '${sheet.name}' has an unexpected header structure`, diagnostics, null);
      for (const group of result.groups) {
        if (codes.has(group.code)) throw this.fail(`Group '${group.code}' appears more than once (sheet '${sheet.name}')`, diagnostics, null);
        codes.add(group.code);
      }
      groups.push(...result.groups);
      entries.push(...result.entries);
      parsedSheets++;
    }
    if (parsedSheets === 0) throw this.fail('The workbook contains no schedule sheets', diagnostics, null);
    if (entries.length === 0) throw this.fail('No lessons found in the workbook', diagnostics, null);
    return { parserKey: this.parserId, period: null, groups, entries, diagnostics: diagnostics.build(this.parserVersion) };
  }

  private fail(message: string, diagnostics: DiagnosticsCollector, cause: unknown): ScheduleParseError {
    return new ScheduleParseError(message, diagnostics.build(this.parserVersion), cause ?? undefined);
  }
}

function isEmptySheet(sheet: SheetView): boolean {
  for (let r = sheet.firstRow; r <= sheet.lastRow; r++) {
    for (const c of sheet.cellColumns(r)) {
      if (!isBlank(sheet.text(r, c))) return false;
    }
  }
  return true;
}
