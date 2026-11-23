import { collapseSpaces } from '../../util/text';
import type { GridFormat } from '../grid/format';
import { GridWorkbookParser } from '../grid/workbookParser';
import { SpoCellTextParser } from './cellText';

/**
 * Парсер расписания факультета СПО (`1_kurs.xls`, `2-3-4_kurs.xls`). Читает ту же «сетку» (группы по столбцам, дни и двухстрочные
 * слоты по строкам, числитель и знаменатель в строках), но по нотации СПО:
 * - занятие это одна строка свободного текста, подгруппа записана «1 под.»;
 * - слот времени может содержать только начало («1) 8 00»), конец = начало + одна пара;
 * - группы бывают в 1, 2 или 4 столбца, многие занятия общие для нескольких групп;
 * - `2-3-4_kurs.xls` не называет семестр: источник должен быть привязан к периоду приложением.
 */
const SPO_FORMAT: GridFormat = {
  lessonTextParser: new SpoCellTextParser(),
  /** «ИСП -255» и «ИСП-255» это одна группа. */
  normalizeGroupCode: (raw) => collapseSpaces(raw).trim().replace(/\s*-\s*/g, '-'),
  /**
   * Занятие над частью столбцов группы без указания подгруппы: ничто не говорит, для кого оно. Показать его всей группе с предупреждением
   * безопаснее, чем скрыть.
   */
  partialSpanPolicy: 'WHOLE_GROUP_WITH_WARNING',
};

export const SPO_PARSER_ID = 'spo-xls';

export class SpoExcelParser extends GridWorkbookParser {
  /** 2: скрытые в Excel столбцы и строки игнорируются, курс группы читается с листа. Совпадает с версией Java-парсера. */
  static readonly VERSION = '2';

  constructor() {
    super(SPO_PARSER_ID, SpoExcelParser.VERSION, SPO_FORMAT, false);
  }
}
