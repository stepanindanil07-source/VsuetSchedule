import type { ParseDiagnostics, ParsedSchedule } from '../model/types';

/**
 * Что приложение знает об источнике до разбора файла. Нужно форматам, которые нельзя прочитать из одного файла.
 */
export interface ParseContext {
  /** Первый день привязанного семестра («YYYY-MM-DD») или null. Заочное расписание пишет даты без года. */
  periodStart: string | null;
  /**
   * Календарный год начала учебного года (2026 для 2026–2027) или null. По нему год набора в коде группы превращается в курс.
   * Берётся у привязанного семестра, а не из файла: файл может указывать семестр неверно (eiu.xlsx).
   */
  academicYearFrom: number | null;
}

export const UNKNOWN_CONTEXT: ParseContext = { periodStart: null, academicYearFrom: null };

/** Контекст по первому дню семестра: учебный год это тот, на который день приходится (с июля). */
export function contextFromPeriodStart(periodStart: string): ParseContext {
  const year = Number(periodStart.slice(0, 4));
  const month = Number(periodStart.slice(5, 7));
  return { periodStart, academicYearFrom: month >= 7 ? year : year - 1 };
}

/** Файл нельзя разобрать вообще (неизвестная структура, нет занятий...): импорт отклоняется, старые данные не трогаются. */
export class ScheduleParseError extends Error {
  constructor(message: string, readonly diagnostics: ParseDiagnostics | null, readonly cause?: unknown) {
    super(message);
    this.name = 'ScheduleParseError';
  }
}

/** Что парсеру передаётся: байты файла и сведения об источнике. */
export interface ParserInput {
  bytes: Uint8Array;
  context: ParseContext;
}

/**
 * Парсер одного формата расписания. Чистая функция: байты на входе, занятия на выходе. Никакой сети, файлов и базы данных.
 * Каждый формат получает отдельную реализацию.
 */
export interface ScheduleParser {
  /** Стабильный идентификатор, хранится в реестре источников. */
  readonly parserId: string;
  /** Растёт, когда результат для того же файла может измениться: тогда файлы разбираются заново. */
  readonly parserVersion: string;
  parse(input: ParserInput): ParsedSchedule;
}
