/** Публичный интерфейс ядра расписания ВГУИТ. Без зависимостей от React Native. */
export * from './model/types';
export * from './parser/types';
export { ParserRegistry } from './parser/registry';
export { DiagnosticsCollector } from './parser/diagnostics';
export { openWorkbook } from './excel/sheetjs';
export { FuitsExcelParser, FUITS_PARSER_ID } from './parsers/fuits/fuitsParser';
export { SpoExcelParser, SPO_PARSER_ID } from './parsers/spo/spoParser';
export { ZaoExcelParser, ZAO_PARSER_ID } from './parsers/zao/zaoParser';
export * from './sources/types';
export { SOURCES, ORGANIZATION_UNITS, KNOWN_GAPS, AUTUMN_2026 } from './sources/registry';
export { buildCalendar, weekTypeOn, validatePeriod, type AcademicCalendar, type CalendarWeek } from './calendar/calendar';
export * from './dataset/types';
export { buildDataset, type BuildInput } from './dataset/builder';
export { publishDataset, type DatasetStore, type PublishOutcome } from './sync/publish';
