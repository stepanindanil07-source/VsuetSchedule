import * as XLSX from 'xlsx';
import type { MergedRange, SheetView, WorkbookView } from './types';

/** Строка листа: столбец → текст. Только непустые ячейки. */
type RowTexts = Map<number, string>;

class SheetJsSheetView implements SheetView {
  readonly name: string;
  readonly firstRow: number;
  readonly lastRow: number;
  private readonly rows = new Map<number, RowTexts>();
  private readonly rowColumns = new Map<number, number[]>();
  private readonly merges = new Map<number, MergedRange>();
  private readonly hiddenCols = new Set<number>();
  private readonly hiddenRows = new Set<number>();

  constructor(name: string, ws: XLSX.WorkSheet) {
    this.name = name;
    const ref = ws['!ref'];
    if (!ref) {
      this.firstRow = 0;
      this.lastRow = -1;
      return;
    }
    const range = XLSX.utils.decode_range(ref);
    this.firstRow = range.s.r;
    this.lastRow = range.e.r;

    for (const address of Object.keys(ws)) {
      if (address.charCodeAt(0) === 33) continue; // «!ref», «!merges» и другие служебные ключи
      const cell = ws[address] as XLSX.CellObject;
      const text = cellText(cell);
      if (text === '') continue;
      const pos = XLSX.utils.decode_cell(address);
      let row = this.rows.get(pos.r);
      if (!row) {
        row = new Map();
        this.rows.set(pos.r, row);
      }
      row.set(pos.c, text);
    }
    for (const [r, row] of this.rows) {
      this.rowColumns.set(r, [...row.keys()].sort((a, b) => a - b));
    }

    // Ключ «строка × 16384 + столбец» помещается в точное целое число (строк не более 1 048 576).
    for (const m of ws['!merges'] ?? []) {
      const range: MergedRange = { firstRow: m.s.r, lastRow: m.e.r, firstCol: m.s.c, lastCol: m.e.c };
      for (let r = range.firstRow; r <= range.lastRow; r++) {
        for (let c = range.firstCol; c <= range.lastCol; c++) this.merges.set(r * 16384 + c, range);
      }
    }
    (ws['!cols'] ?? []).forEach((col, c) => {
      if (col?.hidden) this.hiddenCols.add(c);
    });
    (ws['!rows'] ?? []).forEach((row, r) => {
      if (row?.hidden) this.hiddenRows.add(r);
    });
  }

  text(row: number, col: number): string {
    return this.rows.get(row)?.get(col) ?? '';
  }

  cellColumns(row: number): readonly number[] {
    return this.rowColumns.get(row) ?? [];
  }

  mergedAt(row: number, col: number): MergedRange | null {
    return this.merges.get(row * 16384 + col) ?? null;
  }

  isColumnHidden(col: number): boolean {
    return this.hiddenCols.has(col);
  }

  isRowHidden(row: number): boolean {
    return this.hiddenRows.has(row);
  }
}

/** Текст так, как его показывает Excel (`w`), иначе значение. */
function cellText(cell: XLSX.CellObject): string {
  if (cell.w !== undefined) return cell.w;
  if (cell.v === undefined || cell.v === null) return '';
  return String(cell.v);
}

/**
 * Открывает книгу Excel (.xls или .xlsx, формат определяется по содержимому).
 * @throws Error если файл не является книгой Excel
 */
export function openWorkbook(bytes: Uint8Array): WorkbookView {
  // cellStyles нужен для скрытых столбцов и строк, cellText для форматированного текста ячеек
  const wb = XLSX.read(bytes, { type: 'array', cellStyles: true, cellText: true, cellDates: false });
  return { sheets: wb.SheetNames.map((name) => new SheetJsSheetView(name, wb.Sheets[name])) };
}
