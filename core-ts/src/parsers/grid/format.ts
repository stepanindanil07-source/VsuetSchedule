import type { LessonType } from '../../model/types';

/** Результат разбора текста одной ячейки занятия. */
export interface CellContent {
  kind: 'content';
  lessonType: LessonType;
  /** Исходное обозначение типа занятия или null. */
  rawLessonType: string | null;
  subject: string;
  /** Преподаватели через «, » или null. */
  teacher: string | null;
  /** Аудитории через «, » или null. */
  room: string | null;
  /** Подгруппа, названная в ТЕКСТЕ ячейки («1 под.»), или null. */
  subgroup: number | null;
  warnings: Array<{ code: string; message: string }>;
}

/** Текст нельзя превратить в занятие. code это стабильная причина. */
export interface Rejected {
  kind: 'rejected';
  code: string;
  message: string;
}

/**
 * Разбирает текст ячейки занятия. Запись различается у факультетов, поэтому у каждого формата своя реализация;
 * геометрия сетки (недели, подгруппы по объединённым ячейкам) от неё не зависит. Не теряет то, что не умеет классифицировать.
 */
export interface LessonTextParser {
  parse(text: string): CellContent | Rejected;
}

/** Что делать с занятием, покрывающим часть столбцов группы (и не один столбец). */
export type PartialSpanPolicy = 'SKIP_WITH_ERROR' | 'WHOLE_GROUP_WITH_WARNING';

/** Те немногие места, где сетки разных факультетов различаются. Всё остальное общее. */
export interface GridFormat {
  readonly lessonTextParser: LessonTextParser;
  normalizeGroupCode(raw: string): string;
  readonly partialSpanPolicy: PartialSpanPolicy;
}
