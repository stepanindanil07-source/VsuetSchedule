import type { WeekType } from '../model/types';
import type { AcademicPeriod } from '../sources/types';

/**
 * Календарь учебных недель периода: какой неделе какой тип (числитель или знаменатель) соответствует.
 *
 * Правило: недели чередуются от якоря (понедельник первой недели и её тип), введённого вручную или полученного от университета.
 * Чётность ISO-недели НЕ используется. Если достоверного календаря нет (`provided = false`), даты не придумываются: приложение
 * показывает отсутствие подтверждённого календаря и разрешает ручное переключение.
 */

export interface CalendarWeek {
  /** Номер недели с единицы. */
  index: number;
  /** Понедельник («YYYY-MM-DD») и воскресенье той же недели. */
  start: string;
  end: string;
  weekType: Exclude<WeekType, 'EVERY_WEEK'>;
}

export interface AcademicCalendar {
  provided: boolean;
  /** Откуда календарь: введён вручную или получен от университета; null, если его нет. */
  source: 'MANUAL' | 'UNIVERSITY' | null;
  rule: 'ALTERNATING_FROM_ANCHOR' | null;
  anchor: { weekStart: string; weekType: Exclude<WeekType, 'EVERY_WEEK'> } | null;
  /** Недели от якоря до недели, содержащей конец периода. Пусто, если календаря нет. */
  weeks: CalendarWeek[];
}

const DAY_MS = 86_400_000;

function dayNumber(iso: string): number {
  return Math.round(Date.UTC(Number(iso.slice(0, 4)), Number(iso.slice(5, 7)) - 1, Number(iso.slice(8, 10))) / DAY_MS);
}

function isoOf(day: number): string {
  const d = new Date(day * DAY_MS);
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}-${String(d.getUTCDate()).padStart(2, '0')}`;
}

/** 1 = понедельник … 7 = воскресенье. День 0 (1970-01-01) был четвергом. */
function weekdayOf(day: number): number {
  return ((((day + 3) % 7) + 7) % 7) + 1;
}

const isIsoDate = (s: string): boolean => /^\d{4}-\d{2}-\d{2}$/.test(s) && isoOf(dayNumber(s)) === s;

/** Ошибки в описании периода; пусто, если он корректен. Календарь с неверным якорем не публикуется. */
export function validatePeriod(period: AcademicPeriod): string[] {
  const problems: string[] = [];
  if (!isIsoDate(period.startDate) || !isIsoDate(period.endDate)) {
    problems.push(`Period ${period.id}: start and end must be calendar dates (YYYY-MM-DD)`);
    return problems;
  }
  if (period.startDate >= period.endDate) problems.push(`Period ${period.id}: start must be before end`);
  const anchor = period.calendar;
  if (anchor !== null) {
    if (!isIsoDate(anchor.firstWeekStart)) {
      problems.push(`Period ${period.id}: the first week start is not a calendar date`);
    } else {
      if (weekdayOf(dayNumber(anchor.firstWeekStart)) !== 1) problems.push(`Period ${period.id}: the first week must start on a Monday, ${anchor.firstWeekStart} does not`);
      if (anchor.firstWeekStart > period.startDate || dayNumber(period.startDate) - dayNumber(anchor.firstWeekStart) > 6) {
        problems.push(`Period ${period.id}: the first week (${anchor.firstWeekStart}) must contain the first day of the period (${period.startDate})`);
      }
    }
  }
  return problems;
}

/** Календарь периода. Для периода без якоря это явное «не предоставлен». */
export function buildCalendar(period: AcademicPeriod): AcademicCalendar {
  const anchor = period.calendar;
  if (anchor === null) return { provided: false, source: null, rule: null, anchor: null, weeks: [] };
  const first = dayNumber(anchor.firstWeekStart);
  const last = dayNumber(period.endDate);
  const other = anchor.firstWeekType === 'NUMERATOR' ? 'DENOMINATOR' : 'NUMERATOR';
  const weeks: CalendarWeek[] = [];
  for (let i = 0, start = first; start <= last; i++, start += 7) {
    weeks.push({ index: i + 1, start: isoOf(start), end: isoOf(start + 6), weekType: i % 2 === 0 ? anchor.firstWeekType : other });
  }
  return { provided: true, source: anchor.source, rule: 'ALTERNATING_FROM_ANCHOR', anchor: { weekStart: anchor.firstWeekStart, weekType: anchor.firstWeekType }, weeks };
}

/**
 * Тип недели, на которую приходится дата, или null: календаря нет либо дата вне известных недель периода. null означает
 * «неизвестно», а не «каждая неделя»: приложение в этом случае просит выбрать тип недели вручную.
 */
export function weekTypeOn(calendar: AcademicCalendar, isoDate: string): Exclude<WeekType, 'EVERY_WEEK'> | null {
  if (!calendar.provided || calendar.weeks.length === 0 || !isIsoDate(isoDate)) return null;
  if (isoDate < calendar.weeks[0].start || isoDate > calendar.weeks[calendar.weeks.length - 1].end) return null;
  const weekIndex = Math.floor((dayNumber(isoDate) - dayNumber(calendar.weeks[0].start)) / 7);
  return calendar.weeks[weekIndex].weekType;
}
