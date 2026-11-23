import type { ParsedSchedule } from '../model/types';
import type { AcademicCalendar } from '../calendar/calendar';
import type { AcademicPeriod, KnownGap, OrganizationUnit, ScheduleSource } from '../sources/types';

/**
 * Версия СТРУКТУРЫ набора данных (формы JSON и таблиц SQLite). Растёт только при несовместимом изменении структуры.
 * Не связана с версией СОДЕРЖИМОГО (datasetVersion): расписание меняется каждую неделю, структура почти никогда.
 */
export const SCHEMA_VERSION = 1;

export interface DatasetGroup {
  /** Естественный ключ «подразделение/код»: не меняется между пересборками, по нему приложение хранит выбранную группу. */
  id: string;
  organizationUnitId: string;
  name: string;
  /** Курс, если применимо и известен. */
  course: number | null;
  /** Учебная программа: ни один источник её пока не называет. */
  program: string | null;
  /** Число подгрупп: наибольший номер подгруппы, встретившийся в занятиях группы; null, если подгрупп в занятиях нет. */
  subgroupCount: number | null;
}

/** Занятие в виде, который сохраняется в SQLite. Имена полей по ТЗ мобильного приложения. */
export interface DatasetEntry {
  id: string;
  organizationUnitId: string;
  groupId: string;
  academicPeriodId: string;
  subject: string;
  lessonType: string;
  rawLessonType: string | null;
  teacher: string | null;
  classroom: string | null;
  /** 1 = понедельник … 7 = воскресенье. */
  dayOfWeek: number;
  /** Дата разового занятия (заочная форма) или null. */
  specificDate: string | null;
  startTime: string;
  endTime: string;
  weekType: string;
  /** null = занятие для всей группы. Число подгрупп не ограничено. */
  subgroupNumber: number | null;
  sourceId: string;
  /** Лист и ячейка или диапазон в исходном файле. */
  sourceReference: string | null;
  originalText: string | null;
}

export interface DatasetContent {
  organizationUnits: OrganizationUnit[];
  academicPeriods: Array<Pick<AcademicPeriod, 'id' | 'academicYear' | 'term' | 'startDate' | 'endDate'>>;
  groups: DatasetGroup[];
  scheduleEntries: DatasetEntry[];
  academicCalendar: AcademicCalendar;
}

export interface ManifestSource {
  sourceId: string;
  organizationUnitId: string;
  url: string;
  parserId: string;
  parserVersion: string;
  fileSha256: string;
  fetchedAt: string;
  groups: number;
  entries: number;
}

export interface DatasetManifest {
  datasetId: string;
  /** Версия СОДЕРЖИМОГО; растёт, только когда содержимое изменилось. */
  datasetVersion: number;
  /** Версия СТРУКТУРЫ набора. */
  schemaVersion: number;
  academicPeriod: Pick<AcademicPeriod, 'id' | 'academicYear' | 'term' | 'startDate' | 'endDate'>;
  generatedAt: string;
  /** SHA-256 содержимого (без manifest и без времени): одинаковые данные дают одинаковый хеш. */
  contentHash: string;
  /** Размер сериализованного содержимого в байтах (UTF-8). */
  sizeBytes: number;
  counts: { organizationUnits: number; groups: number; scheduleEntries: number };
  sources: ManifestSource[];
  coverage: {
    /** true только если включены все обязательные источники И нет известных пробелов (опубликованных, но не подключённых файлов). */
    complete: boolean;
    requiredSources: number;
    includedSources: number;
    knownGaps: KnownGap[];
  };
  calendar: { provided: boolean; source: 'MANUAL' | 'UNIVERSITY' | null };
  /** Замечания сборки, не мешающие публикации (например, группы без занятий). */
  warnings: BuildProblem[];
}

export interface Dataset {
  manifest: DatasetManifest;
  content: DatasetContent;
}

/** Результат попытки получить и разобрать один источник. */
export type SourceOutcome =
  | { status: 'OK'; parsed: ParsedSchedule; fileSha256: string; fetchedAt: string }
  | { status: 'FAILED'; error: string };

export interface SourceImport {
  source: ScheduleSource;
  outcome: SourceOutcome;
}

/** Предыдущий опубликованный набор: нужен для версии, для «без изменений» и для защиты от резкого падения числа занятий. */
export interface PreviousDataset {
  datasetVersion: number;
  contentHash: string;
  /** Число занятий по источникам в прошлом наборе (sourceId → количество). */
  entriesBySource?: Record<string, number>;
}

export interface BuildProblem {
  code: string;
  message: string;
  sourceId?: string;
}

export type BuildResult =
  | { status: 'BUILT'; dataset: Dataset }
  /** Содержимое совпало с прошлым набором: новая версия не создаётся. */
  | { status: 'UNCHANGED'; datasetVersion: number; contentHash: string; warnings: BuildProblem[] }
  /** Набор не публикуется, прежний остаётся рабочим. reasons: что именно не так. */
  | { status: 'REJECTED'; reasons: BuildProblem[] };

export type Sha256 = (text: string) => string;
