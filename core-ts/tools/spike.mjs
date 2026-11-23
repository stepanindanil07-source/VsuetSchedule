// Пробный запуск SheetJS на реальных файлах: что он умеет читать и сколько это стоит.
// Запуск: node tools/spike.mjs
import { createRequire } from 'node:module';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const require = createRequire(import.meta.url);
const XLSX = require('xlsx');
const dir = new URL('../fixtures/files/', import.meta.url).pathname;

const colLetter = (c) => XLSX.utils.encode_col(c);

for (const file of readdirSync(dir).sort()) {
  const bytes = readFileSync(join(dir, file));
  const mem0 = process.memoryUsage().heapUsed;
  const t0 = performance.now();
  // cellStyles: нужен для скрытых столбцов/строк; cellText: форматированный текст (.w)
  const wb = XLSX.read(bytes, { type: 'buffer', cellStyles: true, cellText: true, cellDates: false });
  const ms = performance.now() - t0;
  const memMb = (process.memoryUsage().heapUsed - mem0) / 1048576;
  console.log(`\n=== ${file}  (${(bytes.length / 1024).toFixed(0)} КБ)  разбор ${ms.toFixed(0)} мс, +${memMb.toFixed(1)} МБ кучи`);
  for (const name of wb.SheetNames) {
    const ws = wb.Sheets[name];
    if (!ws['!ref']) { console.log(`  лист '${name}': пустой`); continue; }
    const range = XLSX.utils.decode_range(ws['!ref']);
    const merges = ws['!merges'] ?? [];
    const cols = ws['!cols'] ?? [];
    const rows = ws['!rows'] ?? [];
    const hiddenCols = cols.map((c, i) => (c && c.hidden ? colLetter(i) : null)).filter(Boolean);
    const hiddenRows = rows.map((r, i) => (r && r.hidden ? i + 1 : null)).filter(Boolean);
    let nonEmpty = 0, withCyr = 0;
    for (const addr of Object.keys(ws)) {
      if (addr[0] === '!') continue;
      const cell = ws[addr];
      const text = cell.w ?? (cell.v == null ? '' : String(cell.v));
      if (text.trim() !== '') { nonEmpty++; if (/[А-Яа-яЁё]/.test(text)) withCyr++; }
    }
    const abbreviate = (a, n = 14) => (a.length > n ? `${a.slice(0, n).join(',')}…(+${a.length - n})` : a.join(','));
    console.log(`  лист '${name}': ${XLSX.utils.encode_range(range)}, объединений ${merges.length}, непустых ячеек ${nonEmpty} (с кириллицей ${withCyr})`);
    console.log(`     скрытые столбцы: [${abbreviate(hiddenCols)}]  скрытых строк: ${hiddenRows.length}${hiddenRows.length ? ' [' + abbreviate(hiddenRows, 8) + ']' : ''}`);
  }
}
