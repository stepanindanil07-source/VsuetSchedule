import type { MergedRange } from './types';

/** Номер столбца в буквы: 0 → A, 26 → AA. */
export function columnLetters(col: number): string {
  let n = col;
  let s = '';
  do {
    s = String.fromCharCode(65 + (n % 26)) + s;
    n = Math.floor(n / 26) - 1;
  } while (n >= 0);
  return s;
}

/** Ячейка в виде «C12» (строки с единицы, как в Excel). */
export function cellRef(row: number, col: number): string {
  return `${columnLetters(col)}${row + 1}`;
}

/** Диапазон в виде «C12:D13». */
export function rangeRef(r: MergedRange): string {
  return `${cellRef(r.firstRow, r.firstCol)}:${cellRef(r.lastRow, r.lastCol)}`;
}

/** Левая верхняя ячейка диапазона: только она несёт значение. */
export function isAnchor(range: MergedRange, row: number, col: number): boolean {
  return range.firstRow === row && range.firstCol === col;
}
