import { useMemo } from 'react';
import { resolveWeekType, type PairedWeek, type WeekResolution } from '../../domain/weeks';
import { useAppStore } from './StoreContext';

/** Функция «тип недели на дату» по календарю набора и ручной настройке из хранилища. */
export function useWeekResolver(): (isoDate: string) => WeekResolution {
  const calendar = useAppStore((s) => s.calendar);
  const override = useAppStore((s) => s.weekOverride);
  return useMemo(() => (isoDate: string) => resolveWeekType(isoDate, calendar, override), [calendar, override]);
}

export const pairedOnly = (r: WeekResolution): PairedWeek | null => r.type;
