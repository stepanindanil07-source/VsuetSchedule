import type { WeekType } from '../model/types';

/** Тип подразделения. Строка, а не закрытый список: список расширяем, клиент терпим к неизвестным значениям. */
export type UnitType = 'FACULTY' | 'DEPARTMENT' | 'COLLEGE' | 'OTHER' | (string & {});

export interface OrganizationUnit {
  id: string;
  name: string;
  type: UnitType;
}

/**
 * Один опубликованный файл расписания. URL описаны только здесь (реестр источников), а не разбросаны по коду.
 * У подразделения может быть несколько файлов; в одном файле может быть несколько листов.
 */
export interface ScheduleSource {
  id: string;
  organizationUnitId: string;
  name: string;
  url: string;
  format: 'XLS' | 'XLSX';
  /** Какой парсер читает этот файл. Неизвестный идентификатор отклоняется, ничего не угадывается. */
  parserId: string;
  /** Обязателен для полного набора: без него набор не публикуется. */
  required: boolean;
  enabled: boolean;
  /**
   * Учебный период, к которому привязан файл, или null (тогда семестр берётся из самого файла). Нужно файлам, где семестра нет
   * (СПО 2–4 курсов, заочка) или он указан неверно (eiu.xlsx написан «2025-2026»).
   */
  periodId: string | null;
  /** Курс групп этого файла, если сам файл его не называет (файл заочки 5 курса). */
  defaultCourse: number | null;
}

/** Файл, который университет опубликовал, но приложение ещё не подключило: честно перечисляется как пробел полноты. */
export interface KnownGap {
  url: string;
  name: string;
  reason: string;
}

/** Учебный период (семестр) и его календарь недель. Календарь вводится вручную и помечается как ручной. */
export interface AcademicPeriod {
  id: string;
  /** Например «2026-2027». */
  academicYear: string;
  /** Календарный год начала учебного года. */
  yearFrom: number;
  term: 'AUTUMN' | 'SPRING';
  startDate: string;
  endDate: string;
  /**
   * Календарь числителя и знаменателя: понедельник первой недели и её тип; null, если достоверного календаря нет. Тогда приложение
   * не придумывает соответствие дат и разрешает ручное переключение. Чётность ISO-недели НЕ используется.
   */
  calendar: { firstWeekStart: string; firstWeekType: Exclude<WeekType, 'EVERY_WEEK'>; source: 'MANUAL' | 'UNIVERSITY' } | null;
}
