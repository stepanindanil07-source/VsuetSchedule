import type { CalendarWeek } from '@core';
import { dayNumber, mondayOf } from './dates';

export type PairedWeek = 'NUMERATOR' | 'DENOMINATOR';

/**
 * Ручная настройка: пользователь указал, какой тип у недели, в которую входит `anchorWeekStart` (понедельник). Остальные недели
 * чередуются от неё. Пока настройка задана, она важнее календаря набора: человек видит расписание на стене и знает лучше.
 */
export interface WeekOverride {
  anchorWeekStart: string;
  type: PairedWeek;
}

export type WeekOrigin = 'MANUAL' | 'CALENDAR' | 'UNKNOWN';

export interface WeekResolution {
  type: PairedWeek | null;
  origin: WeekOrigin;
}

const other = (t: PairedWeek): PairedWeek => (t === 'NUMERATOR' ? 'DENOMINATOR' : 'NUMERATOR');

/**
 * Тип недели на дату. Порядок: ручная настройка, календарь набора, «неизвестно». Чётность недели ISO и другие догадки не
 * используются: если ни настройки, ни календаря (или дата вне известных недель) нет, ответ null, и приложение просит выбрать.
 */
export function resolveWeekType(isoDate: string, calendar: readonly CalendarWeek[], override: WeekOverride | null): WeekResolution {
  if (override !== null) {
    const weeks = Math.floor((dayNumber(mondayOf(isoDate)) - dayNumber(mondayOf(override.anchorWeekStart))) / 7);
    return { type: weeks % 2 === 0 ? override.type : other(override.type), origin: 'MANUAL' };
  }
  const week = calendar.find((w) => isoDate >= w.start && isoDate <= w.end);
  if (week !== undefined) return { type: week.weekType, origin: 'CALENDAR' };
  return { type: null, origin: 'UNKNOWN' };
}

export const weekTypeLabel = (type: PairedWeek | null): string => (type === 'NUMERATOR' ? 'числитель' : type === 'DENOMINATOR' ? 'знаменатель' : 'тип недели неизвестен');

export function parseWeekOverride(raw: string | null): WeekOverride | null {
  if (raw === null) return null;
  try {
    const v = JSON.parse(raw) as Partial<WeekOverride>;
    if (typeof v.anchorWeekStart === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(v.anchorWeekStart) && (v.type === 'NUMERATOR' || v.type === 'DENOMINATOR')) {
      return { anchorWeekStart: v.anchorWeekStart, type: v.type };
    }
  } catch {
    // повреждённая настройка: считаем, что её нет
  }
  return null;
}
