/**
 * Каноническая модель результата разбора. Не зависит от Excel и от React Native.
 * Имена полей совпадают с эталонной выгрузкой Java-парсеров (fixtures/golden), по которой сверяются парсеры.
 */

/** Тип недели. Расширяемый: клиент должен терпимо относиться к неизвестным значениям. */
export type WeekType = 'EVERY_WEEK' | 'NUMERATOR' | 'DENOMINATOR';

/** Тип занятия. OTHER: то, что не удалось классифицировать (исходный текст сохраняется). */
export type LessonType = 'LECTURE' | 'PRACTICE' | 'LAB' | 'EXAM' | 'CREDIT' | 'OTHER';

export type AcademicTerm = 'AUTUMN' | 'SPRING';

/** Семестр, названный в самом файле (не у каждого файла он есть). */
export interface ParsedPeriod {
  yearFrom: number;
  yearTo: number;
  term: AcademicTerm;
}

export interface ParsedGroup {
  /** Код группы как в файле после нормализации, например «У-251». */
  code: string;
  /** Курс, если его удалось определить достоверно. */
  course: number | null;
}

/** Одно занятие. Не нормализует преподавателей и аудитории: они остаются текстом, как в источнике. */
export interface ParsedEntry {
  groupCode: string;
  /** 1 = понедельник … 7 = воскресенье (ISO). */
  dayOfWeek: number;
  /** Календарная дата разового занятия (заочное обучение), иначе null. Тогда weekType = EVERY_WEEK. */
  date: string | null;
  /** «HH:mm» */
  startTime: string;
  endTime: string;
  weekType: WeekType;
  /** Номер подгруппы с 1; null = занятие для всей группы. Число подгрупп не ограничено. */
  subgroup: number | null;
  subject: string;
  lessonType: LessonType;
  /** Исходное обозначение типа занятия как в файле («пр.», «(лаб.)», «экз.») или null, если его не было. */
  rawLessonType: string | null;
  /** Преподаватели через «, » или null. */
  teacher: string | null;
  /** Аудитории через «, » или null. */
  room: string | null;
  /** Исходный текст занятия: никогда не теряется. */
  rawText: string;
  /** Лист и координаты исходной ячейки или объединённого диапазона. */
  sheet: string;
  range: string;
}

export type IssueSeverity = 'WARNING' | 'ERROR';

export interface ParseIssue {
  severity: IssueSeverity;
  code: string;
  message: string;
  sheet: string | null;
  range: string | null;
  rawText: string | null;
}

export interface ParseDiagnostics {
  parserVersion: string;
  totalFound: number;
  recognized: number;
  skipped: number;
  issues: ParseIssue[];
}

export interface ParsedSchedule {
  parserKey: string;
  /** Семестр из файла или null, если файл его не указывает (тогда нужна привязка источника к периоду). */
  period: ParsedPeriod | null;
  groups: ParsedGroup[];
  entries: ParsedEntry[];
  diagnostics: ParseDiagnostics;
}
