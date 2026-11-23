// Замер первоначальной загрузки на ЭТОМ компьютере: разбор восьми файлов, сборка набора, загрузка в SQLite.
// Цифры телефона будут другими (медленнее); замер на реальном Android-устройстве остаётся обязательным.
// Запуск из core-ts:  npx tsc --outDir /tmp/core-bench --module commonjs --target es2020 --skipLibCheck --esModuleInterop src/index.ts tools/benchmark.ts && node /tmp/core-bench/tools/benchmark.js
import { createHash } from 'node:crypto';
import { readFileSync, statSync, rmSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';
import { gzipSync } from 'node:zlib';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { AUTUMN_2026, buildDataset, contextFromPeriodStart, FuitsExcelParser, KNOWN_GAPS, ORGANIZATION_UNITS, ParserRegistry, SOURCES, SpoExcelParser, UNKNOWN_CONTEXT, ZaoExcelParser, type SourceImport } from '../src/index';
import { APP_DB_DDL, DATASET_DB_DDL, datasetInserts } from '../src/storage/schema';

const FILES: Record<string, string> = {
  uits: 'uits.xlsx', itf: 'itf.xlsx', eht: 'eht.xlsx', tf: 'tf.xlsx', eiu: 'eiu.xlsx',
  'fspo-1': 'fspo-1_kurs.xls', 'fspo-234': 'fspo-2-3-4_kurs.xls', 'zao-5': 'zao-5_kurs.xlsx',
};
const dir = join(__dirname, '..', '..', 'fixtures', 'files');
const sha256 = (s: string): string => createHash('sha256').update(s).digest('hex');
const registry = new ParserRegistry([new FuitsExcelParser(), new SpoExcelParser(), new ZaoExcelParser()]);
const ms = (t0: number): string => `${(performance.now() - t0).toFixed(0)} мс`;

let totalBytes = 0;
const parseOnce = (): { imports: SourceImport[]; perFile: Array<[string, number, number, number]> } => {
  const imports: SourceImport[] = [];
  const perFile: Array<[string, number, number, number]> = [];
  for (const source of SOURCES) {
    const bytes = new Uint8Array(readFileSync(join(dir, FILES[source.id])));
    const t0 = performance.now();
    const parsed = registry.get(source.parserId).parse({ bytes, context: source.periodId === null ? UNKNOWN_CONTEXT : contextFromPeriodStart(AUTUMN_2026.startDate) });
    perFile.push([source.id, bytes.length, performance.now() - t0, parsed.entries.length]);
    imports.push({ source, outcome: { status: 'OK', parsed, fileSha256: sha256(String(bytes.length)), fetchedAt: '2026-11-23T10:00:00Z' } });
  }
  return { imports, perFile };
};

const cold = parseOnce(); // первый прогон включает «разогрев» JIT
const warm = parseOnce();
totalBytes = cold.perFile.reduce((n, f) => n + f[1], 0);
console.log(`ИСХОДНЫЕ ФАЙЛЫ: 8 штук, ${(totalBytes / 1024).toFixed(0)} КБ`);
console.log('разбор, мс (первый прогон / повторный): ');
cold.perFile.forEach((f, i) => console.log(`  ${f[0].padEnd(9)} ${(f[1] / 1024).toFixed(0).padStart(4)} КБ  ${f[2].toFixed(0).padStart(4)} / ${warm.perFile[i][2].toFixed(0).padStart(4)}   занятий ${f[3]}`));
console.log(`  ВСЕГО  разбор: ${cold.perFile.reduce((n, f) => n + f[2], 0).toFixed(0)} мс (первый) / ${warm.perFile.reduce((n, f) => n + f[2], 0).toFixed(0)} мс (повторный)`);

let t0 = performance.now();
const result = buildDataset({ sources: SOURCES, imports: warm.imports, units: ORGANIZATION_UNITS, period: AUTUMN_2026, periods: [AUTUMN_2026], knownGaps: KNOWN_GAPS, previous: null, now: new Date().toISOString(), sha256 });
if (result.status !== 'BUILT') throw new Error('набор не собрался');
console.log(`СБОРКА НАБОРА: ${ms(t0)}  (${result.dataset.manifest.counts.scheduleEntries} занятий, ${result.dataset.manifest.counts.groups} групп)`);
const json = JSON.stringify(result.dataset.content);
console.log(`  JSON содержимого ${(json.length / 1048576).toFixed(2)} МБ, сжатый gzip ${(gzipSync(json).length / 1024).toFixed(0)} КБ`);

const path = join(tmpdir(), `vsuet-bench-${process.pid}.db`);
t0 = performance.now();
const db = new DatabaseSync(path);
db.exec('PRAGMA foreign_keys = ON');
DATASET_DB_DDL.forEach((s) => db.exec(s));
const stmts = datasetInserts(result.dataset);
const tPrep = performance.now() - t0;
t0 = performance.now();
db.exec('BEGIN');
for (const s of stmts) db.prepare(s.sql).run(...s.params);
db.exec('COMMIT');
const tInsert = performance.now() - t0;
db.close();
console.log(`SQLITE: подготовка ${tPrep.toFixed(0)} мс, ${stmts.length} вставок в одной транзакции ${tInsert.toFixed(0)} мс, файл ${(statSync(path).size / 1048576).toFixed(2)} МБ`);
rmSync(path);
void APP_DB_DDL;
