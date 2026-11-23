import type { Dataset } from '../dataset/types';

/**
 * Схема локальной SQLite. Две базы с разной жизнью:
 *
 * 1) БАЗА НАБОРА (dataset.db) пересоздаётся при каждом новом наборе и заменяется ОДНИМ действием (собрать рядом, затем подменить
 *    файл), поэтому пользователь никогда не видит частично обновлённое расписание, а при ошибке остаётся прежняя база.
 * 2) БАЗА ПРИЛОЖЕНИЯ (app.db) живёт всегда: настройки, история синхронизаций, диагностика, сведения о загрузке файлов. Подмена набора её
 *    не затрагивает, поэтому выбор группы и ручное переключение недели переживают обновления.
 *
 * Версия локальной схемы (`PRAGMA user_version`) растёт при изменении структуры таблиц и управляет миграциями; она не связана ни с
 * версией содержимого набора (datasetVersion), ни с версией структуры набора (schemaVersion).
 * Выбранная группа хранится по естественному ключу «подразделение/код» (study_groups.id), а не по номеру.
 */

export const LOCAL_DB_SCHEMA_VERSION = 1;

export const DATASET_DB_DDL: readonly string[] = [
  `CREATE TABLE organization_units (
     id   TEXT PRIMARY KEY,
     name TEXT NOT NULL,
     type TEXT NOT NULL
   )`,
  `CREATE TABLE academic_periods (
     id            TEXT PRIMARY KEY,
     academic_year TEXT NOT NULL,
     term          TEXT NOT NULL,
     start_date    TEXT NOT NULL,
     end_date      TEXT NOT NULL
   )`,
  `CREATE TABLE study_groups (
     id                   TEXT PRIMARY KEY,
     organization_unit_id TEXT NOT NULL REFERENCES organization_units (id),
     name                 TEXT NOT NULL,
     course               INTEGER,
     program              TEXT,
     subgroup_count       INTEGER
   )`,
  `CREATE INDEX idx_groups_unit_course ON study_groups (organization_unit_id, course, name)`,
  `CREATE TABLE schedule_entries (
     id                   TEXT PRIMARY KEY,
     organization_unit_id TEXT NOT NULL REFERENCES organization_units (id),
     group_id             TEXT NOT NULL REFERENCES study_groups (id),
     academic_period_id   TEXT NOT NULL REFERENCES academic_periods (id),
     subject              TEXT NOT NULL,
     lesson_type          TEXT NOT NULL,
     raw_lesson_type      TEXT,
     teacher              TEXT,
     classroom            TEXT,
     day_of_week          INTEGER NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
     specific_date        TEXT,
     start_time           TEXT NOT NULL,
     end_time             TEXT NOT NULL,
     week_type            TEXT NOT NULL,
     subgroup_number      INTEGER CHECK (subgroup_number IS NULL OR subgroup_number >= 1),
     source_id            TEXT NOT NULL,
     source_reference     TEXT,
     original_text        TEXT
   )`,
  // Запросы приложения: занятия группы на день недели, занятия группы на дату.
  `CREATE INDEX idx_entries_group_day ON schedule_entries (group_id, day_of_week, start_time)`,
  `CREATE INDEX idx_entries_group_date ON schedule_entries (group_id, specific_date)`,
  `CREATE TABLE academic_calendar (
     week_index INTEGER PRIMARY KEY,
     start_date TEXT NOT NULL,
     end_date   TEXT NOT NULL,
     week_type  TEXT NOT NULL
   )`,
  // Версия набора, контрольная сумма, manifest целиком, признак календаря.
  `CREATE TABLE dataset_metadata (
     key   TEXT PRIMARY KEY,
     value TEXT NOT NULL
   )`,
  `PRAGMA user_version = ${LOCAL_DB_SCHEMA_VERSION}`,
];

export const APP_DB_DDL: readonly string[] = [
  `CREATE TABLE user_settings (
     key   TEXT PRIMARY KEY,
     value TEXT NOT NULL
   )`,
  // Сведения о последней проверке и загрузке каждого файла: по ним проверка обновлений обходится без повторной загрузки.
  `CREATE TABLE source_metadata (
     source_id       TEXT PRIMARY KEY,
     url             TEXT NOT NULL,
     etag            TEXT,
     last_modified   TEXT,
     file_sha256     TEXT,
     parser_id       TEXT,
     parser_version  TEXT,
     last_checked_at TEXT,
     last_fetched_at TEXT,
     entries         INTEGER,
     skipped         INTEGER
   )`,
  `CREATE TABLE sync_history (
     id             INTEGER PRIMARY KEY AUTOINCREMENT,
     started_at     TEXT NOT NULL,
     finished_at    TEXT,
     outcome        TEXT NOT NULL,
     dataset_version INTEGER,
     message        TEXT
   )`,
  `CREATE TABLE parse_diagnostics (
     id          INTEGER PRIMARY KEY AUTOINCREMENT,
     sync_id     INTEGER REFERENCES sync_history (id) ON DELETE CASCADE,
     source_id   TEXT NOT NULL,
     severity    TEXT NOT NULL,
     code        TEXT NOT NULL,
     message     TEXT NOT NULL,
     sheet       TEXT,
     range       TEXT,
     raw_text    TEXT
   )`,
  `CREATE INDEX idx_diagnostics_sync ON parse_diagnostics (sync_id)`,
  `PRAGMA user_version = ${LOCAL_DB_SCHEMA_VERSION}`,
];

export interface SqlStatement {
  sql: string;
  params: Array<string | number | null>;
}

/**
 * Вставки всего набора в базу набора. Выполняются приложением в ОДНОЙ транзакции в новом файле базы; по окончании файл подменяет
 * рабочий. Порядок учитывает внешние ключи.
 */
export function datasetInserts(dataset: Dataset): SqlStatement[] {
  const c = dataset.content;
  const out: SqlStatement[] = [];
  for (const u of c.organizationUnits) out.push({ sql: 'INSERT INTO organization_units (id, name, type) VALUES (?, ?, ?)', params: [u.id, u.name, u.type] });
  for (const p of c.academicPeriods) {
    out.push({ sql: 'INSERT INTO academic_periods (id, academic_year, term, start_date, end_date) VALUES (?, ?, ?, ?, ?)', params: [p.id, p.academicYear, p.term, p.startDate, p.endDate] });
  }
  for (const g of c.groups) {
    out.push({ sql: 'INSERT INTO study_groups (id, organization_unit_id, name, course, program, subgroup_count) VALUES (?, ?, ?, ?, ?, ?)', params: [g.id, g.organizationUnitId, g.name, g.course, g.program, g.subgroupCount] });
  }
  for (const e of c.scheduleEntries) {
    out.push({
      sql: `INSERT INTO schedule_entries (id, organization_unit_id, group_id, academic_period_id, subject, lesson_type, raw_lesson_type, teacher, classroom,
              day_of_week, specific_date, start_time, end_time, week_type, subgroup_number, source_id, source_reference, original_text)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      params: [e.id, e.organizationUnitId, e.groupId, e.academicPeriodId, e.subject, e.lessonType, e.rawLessonType, e.teacher, e.classroom, e.dayOfWeek, e.specificDate, e.startTime, e.endTime, e.weekType, e.subgroupNumber, e.sourceId, e.sourceReference, e.originalText],
    });
  }
  for (const w of c.academicCalendar.weeks) {
    out.push({ sql: 'INSERT INTO academic_calendar (week_index, start_date, end_date, week_type) VALUES (?, ?, ?, ?)', params: [w.index, w.start, w.end, w.weekType] });
  }
  const m = dataset.manifest;
  const meta: Array<[string, string]> = [
    ['datasetId', m.datasetId],
    ['datasetVersion', String(m.datasetVersion)],
    ['schemaVersion', String(m.schemaVersion)],
    ['contentHash', m.contentHash],
    ['generatedAt', m.generatedAt],
    ['calendarProvided', m.calendar.provided ? '1' : '0'],
    ['calendarSource', m.calendar.source ?? ''],
    ['coverageComplete', m.coverage.complete ? '1' : '0'],
    ['manifest', JSON.stringify(m)],
  ];
  for (const [key, value] of meta) out.push({ sql: 'INSERT INTO dataset_metadata (key, value) VALUES (?, ?)', params: [key, value] });
  return out;
}
