import { createHash } from 'node:crypto';
import type { BuildInput } from '../src/dataset/builder';
import type { SourceImport } from '../src/dataset/types';
import { FuitsExcelParser } from '../src/parsers/fuits/fuitsParser';
import { SpoExcelParser } from '../src/parsers/spo/spoParser';
import { ZaoExcelParser } from '../src/parsers/zao/zaoParser';
import { ParserRegistry } from '../src/parser/registry';
import { contextFromPeriodStart, UNKNOWN_CONTEXT } from '../src/parser/types';
import { AUTUMN_2026, KNOWN_GAPS, ORGANIZATION_UNITS, SOURCES } from '../src/sources/registry';
import type { ScheduleSource } from '../src/sources/types';
import { fileBytes } from './support';

export const sha256 = (text: string): string => createHash('sha256').update(text).digest('hex');

/** Какой файл в fixtures/files соответствует какому источнику реестра. */
export const FILE_OF: Record<string, string> = {
  uits: 'uits.xlsx',
  itf: 'itf.xlsx',
  eht: 'eht.xlsx',
  tf: 'tf.xlsx',
  eiu: 'eiu.xlsx',
  'fspo-1': 'fspo-1_kurs.xls',
  'fspo-234': 'fspo-2-3-4_kurs.xls',
  'zao-5': 'zao-5_kurs.xlsx',
};

export const registry = new ParserRegistry([new FuitsExcelParser(), new SpoExcelParser(), new ZaoExcelParser()]);

/** Разбирает файл источника так, как это сделало бы приложение: контекст берётся у привязанного периода. */
export function importSource(source: ScheduleSource, periodStart: string = AUTUMN_2026.startDate): SourceImport {
  const bytes = fileBytes(FILE_OF[source.id]);
  const context = source.periodId === null ? UNKNOWN_CONTEXT : contextFromPeriodStart(periodStart);
  const parsed = registry.get(source.parserId).parse({ bytes, context });
  return { source, outcome: { status: 'OK', parsed, fileSha256: sha256(Buffer.from(bytes).toString('base64')), fetchedAt: '2026-11-23T10:00:00Z' } };
}

let cached: SourceImport[] | null = null;

/** Разбор восьми файлов занимает около секунды; результат кэшируется, тесты получают независимые копии (они меняют данные). */
export function allImports(): SourceImport[] {
  if (cached === null) cached = SOURCES.map((s) => importSource(s));
  return structuredClone(cached);
}

export function baseInput(overrides: Partial<BuildInput> = {}): BuildInput {
  return {
    sources: SOURCES,
    imports: allImports(),
    units: ORGANIZATION_UNITS,
    period: AUTUMN_2026,
    periods: [AUTUMN_2026],
    knownGaps: KNOWN_GAPS,
    previous: null,
    now: '2026-11-23T12:00:00Z',
    sha256,
    ...overrides,
  };
}
