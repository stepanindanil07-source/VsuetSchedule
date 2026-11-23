import { FuitsExcelParser } from '../src/parsers/fuits/fuitsParser';
import { contextFromPeriodStart } from '../src/parser/types';
import { entryKey, fileBytes, firstDifferences, golden } from './support';

/**
 * Главная проверка переноса: TypeScript-парсер обязан выдавать то же, что Java-парсер backend, на настоящих файлах пяти факультетов.
 * Контекст такой же, как у настоящего импорта привязанного источника: семестр 2026–2027, начало 2026-09-01.
 */
const CONTEXT = contextFromPeriodStart('2026-09-01');
const FILES = ['uits', 'itf', 'eht', 'tf', 'eiu'];
/** Независимые контрольные числа (считались отдельно от эталона при живом импорте и в тестах Java). */
const KNOWN: Record<string, { groups: number; entries: number }> = {
  uits: { groups: 45, entries: 1066 },
  itf: { groups: 39, entries: 813 },
  eht: { groups: 29, entries: 712 },
  tf: { groups: 50, entries: 1382 },
  eiu: { groups: 45, entries: 881 },
};

describe.each(FILES)('FuitsExcelParser на реальном файле %s.xlsx совпадает с эталоном Java', (name) => {
  const expected = golden(name);
  const actual = new FuitsExcelParser().parse({ bytes: fileBytes(name + '.xlsx'), context: CONTEXT });

  it('идентификатор и версия парсера', () => {
    expect(actual.parserKey).toBe(expected.parserKey);
    expect(actual.diagnostics.parserVersion).toBe(expected.parserVersion);
  });

  it('контрольные числа: групп и занятий', () => {
    expect({ groups: actual.groups.length, entries: actual.entries.length }).toEqual(KNOWN[name]);
  });

  it('семестр из файла', () => {
    expect(actual.period).toEqual(expected.period);
  });

  it('группы и их курсы, в том же порядке', () => {
    expect(firstDifferences(actual.groups, expected.groups, (g) => JSON.stringify(g))).toEqual([]);
    expect(actual.groups).toHaveLength(expected.groups.length);
  });

  it('каждое занятие: день, время, тип недели, подгруппа, предмет, тип, преподаватель, аудитория, исходный текст, ячейка', () => {
    expect(firstDifferences(actual.entries, expected.entries, entryKey)).toEqual([]);
    expect(actual.entries).toHaveLength(expected.entries.length);
  });

  it('счётчики диагностики', () => {
    expect({ total: actual.diagnostics.totalFound, ok: actual.diagnostics.recognized, skipped: actual.diagnostics.skipped }).toEqual({
      total: expected.diagnostics.totalFound,
      ok: expected.diagnostics.recognized,
      skipped: expected.diagnostics.skipped,
    });
  });

  it('замечания диагностики: серьёзность, код, сообщение, лист и ячейка', () => {
    const strip = (i: { severity: string; code: string; message: string; sheet: string | null; range: string | null }) =>
      JSON.stringify({ severity: i.severity, code: i.code, message: i.message, sheet: i.sheet, range: i.range });
    expect(firstDifferences(actual.diagnostics.issues, expected.diagnostics.issues, strip)).toEqual([]);
    expect(actual.diagnostics.issues).toHaveLength(expected.diagnostics.issues.length);
  });
});
