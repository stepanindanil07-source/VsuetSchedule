import { DatabaseSync } from 'node:sqlite';
import { buildDataset } from '../src/dataset/builder';
import type { Dataset } from '../src/dataset/types';
import { APP_DB_DDL, DATASET_DB_DDL, datasetInserts, LOCAL_DB_SCHEMA_VERSION } from '../src/storage/schema';
import { baseInput } from './datasetSupport';

/**
 * Совместимость со SQLite: настоящий набор из 8 файлов загружается в SQLite по схеме приложения, внешние ключи включены, и выполняются
 * запросы, которые будет делать приложение при работе офлайн.
 */
const result = buildDataset(baseInput());
if (result.status !== 'BUILT') throw new Error('набор не собрался');
const dataset: Dataset = result.dataset;

function load(): DatabaseSync {
  const db = new DatabaseSync(':memory:');
  db.exec('PRAGMA foreign_keys = ON');
  DATASET_DB_DDL.forEach((sql) => db.exec(sql));
  db.exec('BEGIN');
  for (const s of datasetInserts(dataset)) db.prepare(s.sql).run(...s.params);
  db.exec('COMMIT');
  return db;
}

describe('набор данных в SQLite', () => {
  const db = load();
  const count = (table: string): number => (db.prepare(`SELECT count(*) AS n FROM ${table}`).get() as { n: number }).n;

  it('все строки загружены, внешние ключи соблюдены', () => {
    expect(count('organization_units')).toBe(7);
    expect(count('study_groups')).toBe(300);
    expect(count('schedule_entries')).toBe(7285);
    expect(count('academic_periods')).toBe(1);
    expect(count('academic_calendar')).toBe(18);
    expect(db.prepare('PRAGMA foreign_key_check').all()).toEqual([]);
  });

  it('версия локальной схемы записана', () => {
    expect((db.prepare('PRAGMA user_version').get() as { user_version: number }).user_version).toBe(LOCAL_DB_SCHEMA_VERSION);
  });

  it('метаданные: версия, структура, сумма, manifest целиком', () => {
    const meta = Object.fromEntries((db.prepare('SELECT key, value FROM dataset_metadata').all() as Array<{ key: string; value: string }>).map((r) => [r.key, r.value]));
    expect(meta).toMatchObject({ datasetVersion: '1', schemaVersion: '1', contentHash: dataset.manifest.contentHash, calendarProvided: '1', coverageComplete: '0' });
    expect(JSON.parse(meta.manifest).counts).toEqual({ organizationUnits: 7, groups: 300, scheduleEntries: 7285 });
  });

  it('выбор факультета и курса: список групп', () => {
    const rows = db.prepare(`SELECT name FROM study_groups WHERE organization_unit_id = ? AND course = ? ORDER BY name`).all('uits', 2) as Array<{ name: string }>;
    expect(rows.length).toBeGreaterThan(5);
    expect(rows.map((r) => r.name)).toContain('У-251'); // набор 2025 года это второй курс осенью 2026
  });

  it('расписание группы на день недели для числителя и подгруппы 1 (общие занятия и занятия подгруппы)', () => {
    const rows = db
      .prepare(
        `SELECT start_time, subject, week_type, subgroup_number FROM schedule_entries
         WHERE group_id = ? AND specific_date IS NULL AND day_of_week = ? AND week_type IN ('EVERY_WEEK', ?)
           AND (subgroup_number IS NULL OR subgroup_number = ?) ORDER BY start_time`,
      )
      .all('uits/У-251', 1, 'NUMERATOR', 1) as Array<{ start_time: string; subject: string; week_type: string; subgroup_number: number | null }>;
    expect(rows.length).toBeGreaterThan(0);
    for (const r of rows) {
      expect(['EVERY_WEEK', 'NUMERATOR']).toContain(r.week_type);
      expect([null, 1]).toContain(r.subgroup_number);
    }
    expect(rows.map((r) => r.start_time)).toEqual([...rows.map((r) => r.start_time)].sort());
  });

  it('расписание заочной группы на дату', () => {
    const rows = db.prepare(`SELECT subject FROM schedule_entries WHERE group_id = ? AND specific_date = ?`).all('zao/ЗТП-22', '2026-10-27');
    expect(rows.length).toBeGreaterThan(0);
  });

  it('запросы приложения используют индексы, а не полный перебор 7285 строк', () => {
    const plan = (sql: string, ...args: Array<string | number>): string =>
      (db.prepare(`EXPLAIN QUERY PLAN ${sql}`).all(...args) as Array<{ detail: string }>).map((r) => r.detail).join(' | ');
    expect(plan('SELECT * FROM schedule_entries WHERE group_id = ? AND day_of_week = ?', 'uits/У-251', 1)).toMatch(/idx_entries_group_day/);
    expect(plan('SELECT * FROM schedule_entries WHERE group_id = ? AND specific_date = ?', 'zao/ЗТП-22', '2026-10-27')).toMatch(/idx_entries_group_date/);
    expect(plan('SELECT * FROM study_groups WHERE organization_unit_id = ? AND course = ?', 'uits', 2)).toMatch(/idx_groups_unit_course/);
  });

  it('база отвергает занятие с несуществующей группой (внешний ключ)', () => {
    const e = dataset.content.scheduleEntries[0];
    const stmt = datasetInserts(dataset).find((s) => s.sql.includes('INTO schedule_entries'))!;
    const params = [...stmt.params];
    params[0] = 'x#1';
    params[2] = 'нет/такой-группы';
    expect(e).toBeDefined();
    expect(() => db.prepare(stmt.sql).run(...params)).toThrow(/FOREIGN KEY/);
  });

  it('база отвергает подгруппу меньше единицы (CHECK)', () => {
    const stmt = datasetInserts(dataset).find((s) => s.sql.includes('INTO schedule_entries'))!;
    const params = [...stmt.params];
    params[0] = 'y#1';
    params[14] = 0;
    expect(() => db.prepare(stmt.sql).run(...params)).toThrow(/CHECK/);
  });
});

describe('база приложения', () => {
  it('создаётся и хранит настройки отдельно от набора', () => {
    const db = new DatabaseSync(':memory:');
    APP_DB_DDL.forEach((sql) => db.exec(sql));
    db.prepare('INSERT INTO user_settings (key, value) VALUES (?, ?)').run('selectedGroupId', 'uits/У-251');
    db.prepare('INSERT INTO user_settings (key, value) VALUES (?, ?)').run('manualWeekType', 'NUMERATOR');
    expect((db.prepare('SELECT value FROM user_settings WHERE key = ?').get('selectedGroupId') as { value: string }).value).toBe('uits/У-251');
  });
});
