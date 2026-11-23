/**
 * Мелкие текстовые помощники. Регулярные выражения во всём ядре написаны на явных диапазонах букв, без `\p{L}` и без
 * lookbehind: поддержку этих возможностей движком Hermes (React Native) нельзя проверить без устройства, а явные диапазоны
 * работают везде. Проверено сверкой с эталонной выгрузкой на всех реальных файлах.
 */

/** Заглавные, строчные и любые буквы: латиница и кириллица. */
export const LU = 'A-ZА-ЯЁ';
export const LL = 'a-zа-яё';
export const L = 'A-Za-zА-Яа-яЁё';

/** Пусто или только пробельные символы. */
export function isBlank(s: string): boolean {
  return s.trim() === '';
}

/** Строки текста по любому из переводов строки. */
export function lines(text: string): string[] {
  return text.split(/\r\n|\r|\n/);
}

/** Подряд идущие пробельные символы в один пробел. */
export function collapseSpaces(s: string): string {
  return s.replace(/\s+/g, ' ');
}

/** «HH:mm» из числа минут от начала суток (после полуночи отсчёт идёт заново, как у LocalTime). */
export function formatTime(minutesOfDay: number): string {
  const m = ((minutesOfDay % 1440) + 1440) % 1440;
  return `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;
}

/** Минуты от начала суток; бросает ошибку для несуществующего времени (как LocalTime.of). */
export function minutesOf(hour: number, minute: number): number {
  if (!(hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59)) {
    throw new Error(`Invalid time ${hour}:${minute}`);
  }
  return hour * 60 + minute;
}
