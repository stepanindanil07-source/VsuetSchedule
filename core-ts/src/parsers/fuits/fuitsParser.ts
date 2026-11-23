import type { GridFormat } from '../grid/format';
import { GridWorkbookParser } from '../grid/workbookParser';
import { FuitsCellTextParser } from './cellText';

/**
 * Парсер расписаний факультетов ФУИТС, ИТФ, ЭХТ, ТФ и ЭУ (XLSX): «сетка» с листами по курсам, группами по столбцам, днями и
 * двухстрочными слотами по строкам, числителем и знаменателем в строках, подгруппами в столбцах. Семестр обязателен в файле.
 */
const FUITS_FORMAT: GridFormat = {
  lessonTextParser: new FuitsCellTextParser(),
  normalizeGroupCode: (raw) => raw.trim(),
  partialSpanPolicy: 'SKIP_WITH_ERROR',
};

export const FUITS_PARSER_ID = 'vsuet-grid-xlsx';

export class FuitsExcelParser extends GridWorkbookParser {
  /** 2: курс группы читается с листа; 3: на листах со смесью курсов по коду группы. Совпадает с версией Java-парсера. */
  static readonly VERSION = '3';

  constructor() {
    super(FUITS_PARSER_ID, FuitsExcelParser.VERSION, FUITS_FORMAT, true);
  }
}
