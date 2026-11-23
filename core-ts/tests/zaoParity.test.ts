import { ZaoExcelParser } from '../src/parsers/zao/zaoParser';
import { contextFromPeriodStart, UNKNOWN_CONTEXT } from '../src/parser/types';
import { entryKey, fileBytes, firstDifferences, golden } from './support';

/** Заочное расписание: занятия по календарным датам. Сверка с эталоном Java и проверка требования привязки к семестру. */
describe('ZaoExcelParser на реальном файле zao-5_kurs.xlsx совпадает с эталоном Java', () => {
  const expected = golden('zao-5_kurs');
  const actual = new ZaoExcelParser().parse({ bytes: fileBytes('zao-5_kurs.xlsx'), context: contextFromPeriodStart('2026-09-01') });

  it('контрольные числа: 17 групп, 882 занятия, ни одного пропуска', () => {
    expect(actual.groups).toHaveLength(17);
    expect(actual.entries).toHaveLength(882);
    expect(actual.diagnostics.skipped).toBe(0);
  });
  it('идентификатор, версия, период отсутствует', () => {
    expect([actual.parserKey, actual.diagnostics.parserVersion, actual.period]).toEqual([expected.parserKey, expected.parserVersion, null]);
  });
  it('группы', () => {
    expect(firstDifferences(actual.groups, expected.groups, (g) => JSON.stringify(g))).toEqual([]);
  });
  it('каждое занятие, включая дату', () => {
    expect(firstDifferences(actual.entries, expected.entries, entryKey)).toEqual([]);
    expect(actual.entries).toHaveLength(expected.entries.length);
  });
  it('диагностика', () => {
    const strip = (i: { severity: string; code: string; message: string; sheet: string | null; range: string | null }) =>
      JSON.stringify({ severity: i.severity, code: i.code, message: i.message, sheet: i.sheet, range: i.range });
    expect(firstDifferences(actual.diagnostics.issues, expected.diagnostics.issues, strip)).toEqual([]);
    expect(actual.diagnostics.issues).toHaveLength(expected.diagnostics.issues.length);
  });
  it('все даты внутри файла, день недели совпадает с датой', () => {
    for (const e of actual.entries) {
      expect(e.date).not.toBeNull();
      const d = new Date(`${e.date}T00:00:00Z`).getUTCDay();
      expect(e.dayOfWeek).toBe(d === 0 ? 7 : d);
      expect(e.weekType).toBe('EVERY_WEEK');
    }
  });
});

describe('ZaoExcelParser: год берётся у привязанного семестра, неверная привязка не проходит молча', () => {
  it('без привязки к семестру разбор отклоняется с понятной причиной', () => {
    expect(() => new ZaoExcelParser().parse({ bytes: fileBytes('zao-5_kurs.xlsx'), context: UNKNOWN_CONTEXT })).toThrow(/pin the source/);
  });

  it('семестр, начавшийся годом раньше, сдвигает все дни недели: каждый день отклоняется', () => {
    // «ПНД 12/10» при начале семестра 2025-09-01 это 12 октября 2025, воскресенье, а не понедельник
    expect(() => new ZaoExcelParser().parse({ bytes: fileBytes('zao-5_kurs.xlsx'), context: contextFromPeriodStart('2025-09-01') })).toThrow(
      /No lessons found/,
    );
  });
});
