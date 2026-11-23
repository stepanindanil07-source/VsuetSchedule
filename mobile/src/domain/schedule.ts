import type { EntryRow } from '../storage/datasetRepo';
import { addDays, mondayOf, parseMinutes, weekdayOf } from './dates';
import type { PairedWeek } from './weeks';

export interface DayLesson {
  entry: EntryRow;
  /**
   * Тип недели неизвестен, а занятие бывает только в числитель или только в знаменатель: показываем его с пометкой, а не
   * прячем (потерять занятие хуже, чем показать лишнее).
   */
  weekUncertain: boolean;
}

/**
 * Занятия группы на дату.
 * - Разовые занятия (заочная форма) берутся по дате, повторяющиеся по дню недели.
 * - Тип недели `weekType` null означает «неизвестно»: занятия «только числитель» и «только знаменатель» остаются с пометкой.
 * - Подгруппа `subgroup` null означает «показывать все»; занятие с номером подгруппы тогда подписывается в интерфейсе.
 */
export function lessonsOn(entries: readonly EntryRow[], isoDate: string, weekType: PairedWeek | null, subgroup: number | null): DayLesson[] {
  const weekday = weekdayOf(isoDate);
  const result: DayLesson[] = [];
  for (const entry of entries) {
    if (entry.specificDate !== null ? entry.specificDate !== isoDate : entry.dayOfWeek !== weekday) continue;
    if (subgroup !== null && entry.subgroupNumber !== null && entry.subgroupNumber !== subgroup) continue;
    let weekUncertain = false;
    // у разовых занятий типа недели нет по смыслу; для повторяющихся он обязателен
    if (entry.specificDate === null && entry.weekType !== 'EVERY_WEEK') {
      if (weekType === null) weekUncertain = true;
      else if (entry.weekType !== weekType) continue;
    }
    result.push({ entry, weekUncertain });
  }
  return result.sort(
    (a, b) =>
      (parseMinutes(a.entry.startTime) || 0) - (parseMinutes(b.entry.startTime) || 0) ||
      (parseMinutes(a.entry.endTime) || 0) - (parseMinutes(b.entry.endTime) || 0) ||
      a.entry.subject.localeCompare(b.entry.subject),
  );
}

export type LessonPhase = 'PAST' | 'NOW' | 'NEXT' | 'LATER';

/** Состояние занятий сегодня относительно текущего времени: прошло, идёт сейчас, ближайшее, позже. */
export function phasesAt(lessons: readonly DayLesson[], minutesNow: number): LessonPhase[] {
  let nextAssigned = false;
  return lessons.map((l) => {
    const start = parseMinutes(l.entry.startTime);
    const end = parseMinutes(l.entry.endTime);
    if (Number.isNaN(start) || Number.isNaN(end)) return 'LATER';
    if (minutesNow >= end) return 'PAST';
    if (minutesNow >= start) return 'NOW';
    if (!nextAssigned) {
      nextAssigned = true;
      return 'NEXT';
    }
    return 'LATER';
  });
}

/** Ближайший день с занятиями начиная с даты (включительно), не дальше `horizonDays`; null, если занятий нет. */
export function nextLessonDay(
  entries: readonly EntryRow[],
  from: string,
  weekTypeOn: (iso: string) => PairedWeek | null,
  subgroup: number | null,
  horizonDays = 21,
): string | null {
  for (let i = 0; i <= horizonDays; i++) {
    const date = addDays(from, i);
    if (lessonsOn(entries, date, weekTypeOn(date), subgroup).length > 0) return date;
  }
  return null;
}

/** Даты понедельник–воскресенье недели, в которую входит дата. */
export function weekDates(isoDate: string): string[] {
  const monday = mondayOf(isoDate);
  return Array.from({ length: 7 }, (_, i) => addDays(monday, i));
}

/** Номера подгрупп, которые встречаются в занятиях группы (1..N), для выбора подгруппы. */
export function subgroupNumbers(entries: readonly EntryRow[], declaredCount: number | null): number[] {
  const max = Math.max(declaredCount ?? 0, ...entries.map((e) => e.subgroupNumber ?? 0));
  return Array.from({ length: max }, (_, i) => i + 1);
}

const LESSON_TYPE_LABELS: Record<string, string> = {
  LECTURE: 'Лекция',
  PRACTICE: 'Практика',
  LAB: 'Лабораторная',
  EXAM: 'Экзамен',
  CREDIT: 'Зачёт',
  OTHER: '',
};

/** Название типа занятия для интерфейса; для неклассифицированного (OTHER) пусто, исходное обозначение показывается отдельно. */
export const lessonTypeLabel = (type: string): string => LESSON_TYPE_LABELS[type] ?? '';
