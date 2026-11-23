import { SpoExcelParser } from '../src/parsers/spo/spoParser';
import { contextFromPeriodStart } from '../src/parser/types';
import { entryKey, fileBytes, firstDifferences, golden } from './support';

/** СПО: два файла .xls, свободный текст в ячейках, скрытые столбцы. Сверка с эталоном Java. */
const CONTEXT = contextFromPeriodStart('2026-09-01');
const FILES: Array<{ name: string; file: string; groups: number; entries: number }> = [
  { name: 'fspo-1_kurs', file: 'fspo-1_kurs.xls', groups: 19, entries: 405 },
  { name: 'fspo-2-3-4_kurs', file: 'fspo-2-3-4_kurs.xls', groups: 56, entries: 1144 },
];

describe.each(FILES)('SpoExcelParser на реальном файле $file совпадает с эталоном Java', ({ name, file, groups, entries }) => {
  const expected = golden(name);
  const actual = new SpoExcelParser().parse({ bytes: fileBytes(file), context: CONTEXT });

  it('контрольные числа', () => {
    expect({ groups: actual.groups.length, entries: actual.entries.length }).toEqual({ groups, entries });
  });
  it('идентификатор, версия, семестр', () => {
    expect([actual.parserKey, actual.diagnostics.parserVersion, actual.period]).toEqual([expected.parserKey, expected.parserVersion, expected.period]);
  });
  it('группы и курсы', () => {
    expect(firstDifferences(actual.groups, expected.groups, (g) => JSON.stringify(g))).toEqual([]);
  });
  it('каждое занятие', () => {
    expect(firstDifferences(actual.entries, expected.entries, entryKey)).toEqual([]);
    expect(actual.entries).toHaveLength(expected.entries.length);
  });
  it('счётчики и замечания диагностики', () => {
    const strip = (i: { severity: string; code: string; message: string; sheet: string | null; range: string | null }) =>
      JSON.stringify({ severity: i.severity, code: i.code, message: i.message, sheet: i.sheet, range: i.range });
    expect([actual.diagnostics.totalFound, actual.diagnostics.recognized, actual.diagnostics.skipped]).toEqual([
      expected.diagnostics.totalFound,
      expected.diagnostics.recognized,
      expected.diagnostics.skipped,
    ]);
    expect(firstDifferences(actual.diagnostics.issues, expected.diagnostics.issues, strip)).toEqual([]);
    expect(actual.diagnostics.issues).toHaveLength(expected.diagnostics.issues.length);
  });
});
