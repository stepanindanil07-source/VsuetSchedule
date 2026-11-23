/**
 * Даты как строки «YYYY-MM-DD» и арифметика по номерам дней (UTC). Часовые пояса и переход на летнее время не влияют на
 * результат: «сегодня» берётся как календарная дата на устройстве, а дальше считаются целые дни.
 */

const DAY_MS = 86_400_000;

export function dayNumber(iso: string): number {
  return Math.round(Date.UTC(Number(iso.slice(0, 4)), Number(iso.slice(5, 7)) - 1, Number(iso.slice(8, 10))) / DAY_MS);
}

export function isoOfDay(day: number): string {
  const d = new Date(day * DAY_MS);
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}-${String(d.getUTCDate()).padStart(2, '0')}`;
}

/** Календарная дата на устройстве (местный часовой пояс). */
export function todayIso(now: Date = new Date()): string {
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
}

/** Минуты с начала суток на устройстве. */
export function minutesOfDay(now: Date = new Date()): number {
  return now.getHours() * 60 + now.getMinutes();
}

/** 1 = понедельник … 7 = воскресенье. День 0 (1970-01-01) был четвергом. */
export function weekdayOf(iso: string): number {
  return ((((dayNumber(iso) + 3) % 7) + 7) % 7) + 1;
}

export function addDays(iso: string, days: number): string {
  return isoOfDay(dayNumber(iso) + days);
}

/** Понедельник недели, в которую входит дата. */
export function mondayOf(iso: string): string {
  return addDays(iso, 1 - weekdayOf(iso));
}

/** Время «HH:mm» или «H:mm» в минутах от начала суток; NaN для нечитаемого. */
export function parseMinutes(time: string): number {
  const m = /^(\d{1,2}):(\d{2})$/.exec(time.trim());
  return m === null ? NaN : Number(m[1]) * 60 + Number(m[2]);
}

const MONTHS_GENITIVE = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря'];
const WEEKDAYS = ['понедельник', 'вторник', 'среда', 'четверг', 'пятница', 'суббота', 'воскресенье'];
const WEEKDAYS_SHORT = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс'];

export const weekdayName = (weekday: number): string => WEEKDAYS[weekday - 1] ?? '';
export const weekdayShort = (weekday: number): string => WEEKDAYS_SHORT[weekday - 1] ?? '';

/** «23 ноября». */
export function dayMonth(iso: string): string {
  return `${Number(iso.slice(8, 10))} ${MONTHS_GENITIVE[Number(iso.slice(5, 7)) - 1]}`;
}

/** «понедельник, 23 ноября». */
export function longDate(iso: string): string {
  return `${weekdayName(weekdayOf(iso))}, ${dayMonth(iso)}`;
}
