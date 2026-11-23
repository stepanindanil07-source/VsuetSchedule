/**
 * Узкий интерфейс чтения листа Excel. Парсеры зависят только от него, а не от конкретной библиотеки:
 * SheetJS можно заменить, не трогая ни один парсер. Все номера строк и столбцов нулевые.
 */

export interface MergedRange {
  firstRow: number;
  lastRow: number;
  firstCol: number;
  lastCol: number;
}

export interface SheetView {
  readonly name: string;
  /** Первая и последняя строки листа; для пустого листа lastRow = -1. */
  readonly firstRow: number;
  readonly lastRow: number;
  /** Текст ячейки так, как его показывает Excel; пустая строка, если ячейки нет. */
  text(row: number, col: number): string;
  /** Столбцы ячеек строки с непустым текстом, по возрастанию. */
  cellColumns(row: number): readonly number[];
  /** Объединённый диапазон, накрывающий ячейку, или null. Значение хранит только левая верхняя ячейка. */
  mergedAt(row: number, col: number): MergedRange | null;
  isColumnHidden(col: number): boolean;
  isRowHidden(row: number): boolean;
}

export interface WorkbookView {
  readonly sheets: readonly SheetView[];
}
