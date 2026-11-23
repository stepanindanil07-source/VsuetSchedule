import { DATASET_DB_DDL, datasetInserts, type CalendarWeek, type Dataset, type DatasetManifest, type PreviousDataset } from '@core';
import type { AppRepo } from './appRepo';
import type { Db, DbOpener, SqlValue } from './db';

/**
 * База набора расписания. Две ячейки, `dataset-a.db` и `dataset-b.db`: рабочая та, на которую указывает настройка `activeSlot`
 * в app.db. Новый набор собирается в НЕРАБОЧЕЙ ячейке и проверяется; рабочей она становится одной записью в app.db. Пока сборка
 * не закончилась (ошибка, закрытие приложения, нехватка места), рабочая ячейка не тронута и пользователь видит прежнее
 * расписание. Файлы не переименовываются: подмена это один атомарный UPDATE.
 */

const SLOTS = ['dataset-a.db', 'dataset-b.db'] as const;
const ACTIVE_SLOT_KEY = 'activeDatasetSlot';

export interface DatasetInfo {
  datasetVersion: number;
  contentHash: string;
  generatedAt: string;
  coverageComplete: boolean;
  calendarProvided: boolean;
  manifest: DatasetManifest;
}

export interface UnitRow {
  id: string;
  name: string;
  type: string;
}

export interface GroupRow {
  id: string;
  unitId: string;
  name: string;
  course: number | null;
  subgroupCount: number | null;
}

export interface EntryRow {
  id: string;
  groupId: string;
  subject: string;
  lessonType: string;
  rawLessonType: string | null;
  teacher: string | null;
  classroom: string | null;
  dayOfWeek: number;
  specificDate: string | null;
  startTime: string;
  endTime: string;
  weekType: string;
  subgroupNumber: number | null;
  sourceId: string;
  originalText: string | null;
}

export class DatasetStoreError extends Error {}

export class DatasetManager {
  private active: { slot: string; db: Db } | null = null;

  constructor(
    private readonly opener: DbOpener,
    private readonly app: AppRepo,
  ) {}

  /** Открытая рабочая база или null, если набора ещё нет или он повреждён. */
  async db(): Promise<Db | null> {
    if (this.active !== null) return this.active.db;
    const slot = await this.app.getSetting(ACTIVE_SLOT_KEY);
    if (slot === null || !(SLOTS as readonly string[]).includes(slot)) return null;
    try {
      const db = await this.opener.open(slot);
      if (!(await this.looksValid(db))) {
        await db.close();
        return null;
      }
      this.active = { slot, db };
      return db;
    } catch {
      return null;
    }
  }

  private async looksValid(db: Db): Promise<boolean> {
    try {
      const row = await db.first<{ value: string }>("SELECT value FROM dataset_metadata WHERE key = 'datasetVersion'");
      return row !== null;
    } catch {
      return false;
    }
  }

  async info(): Promise<DatasetInfo | null> {
    const db = await this.db();
    if (db === null) return null;
    try {
      const rows = await db.all<{ key: string; value: string }>('SELECT key, value FROM dataset_metadata');
      const meta = Object.fromEntries(rows.map((r) => [r.key, r.value]));
      const manifest = JSON.parse(meta.manifest) as DatasetManifest;
      return {
        datasetVersion: Number(meta.datasetVersion),
        contentHash: meta.contentHash,
        generatedAt: meta.generatedAt,
        coverageComplete: meta.coverageComplete === '1',
        calendarProvided: meta.calendarProvided === '1',
        manifest,
      };
    } catch {
      return null;
    }
  }

  // ---- DatasetStore (ядро) ----

  async previous(): Promise<PreviousDataset | null> {
    const info = await this.info();
    if (info === null) return null;
    return {
      datasetVersion: info.datasetVersion,
      contentHash: info.contentHash,
      entriesBySource: Object.fromEntries(info.manifest.sources.map((s) => [s.sourceId, s.entries])),
    };
  }

  /** Собирает набор в нерабочей ячейке, проверяет и подменяет рабочую одной записью. Любая ошибка оставляет прежний набор. */
  async replace(dataset: Dataset): Promise<void> {
    const current = this.active?.slot ?? (await this.app.getSetting(ACTIVE_SLOT_KEY));
    const target = current === SLOTS[0] ? SLOTS[1] : SLOTS[0];
    await this.opener.remove(target);
    const db = await this.opener.open(target);
    try {
      await db.exec('PRAGMA synchronous = NORMAL');
      await db.transaction(async () => {
        for (const statement of DATASET_DB_DDL) await db.exec(statement);
        // подряд идущие вставки в одну таблицу выполняются одним подготовленным оператором
        let sql: string | null = null;
        let rows: SqlValue[][] = [];
        for (const insert of datasetInserts(dataset)) {
          if (insert.sql !== sql) {
            if (sql !== null) await db.runMany(sql, rows);
            sql = insert.sql;
            rows = [];
          }
          rows.push(insert.params);
        }
        if (sql !== null) await db.runMany(sql, rows);
      });
      await this.verify(db, dataset);
    } catch (error) {
      await db.close().catch(() => undefined);
      await this.opener.remove(target);
      throw error;
    }
    await db.close();
    // единственное действие, которое переключает пользователя на новый набор
    await this.app.setSetting(ACTIVE_SLOT_KEY, target);
    if (this.active !== null) {
      const old = this.active.db;
      this.active = null;
      await old.close().catch(() => undefined);
    }
  }

  /** Контроль собранной базы: целостность и число строк совпадают с manifest, иначе набор не принимается. */
  private async verify(db: Db, dataset: Dataset): Promise<void> {
    const quick = await db.first<{ quick_check: string }>('PRAGMA quick_check');
    if (quick?.quick_check !== 'ok') throw new DatasetStoreError(`SQLite integrity check failed: ${quick?.quick_check ?? 'no result'}`);
    const counts = dataset.manifest.counts;
    const expected: Array<[string, number]> = [
      ['organization_units', counts.organizationUnits],
      ['study_groups', counts.groups],
      ['schedule_entries', counts.scheduleEntries],
    ];
    for (const [table, count] of expected) {
      const actual = (await db.first<{ n: number }>(`SELECT COUNT(*) AS n FROM ${table}`))?.n;
      if (actual !== count) throw new DatasetStoreError(`Table ${table} has ${actual} rows, the dataset has ${count}`);
    }
  }

  // ---- запросы приложения ----

  async units(): Promise<UnitRow[]> {
    const db = await this.db();
    if (db === null) return [];
    // показываются только подразделения, у которых есть группы
    return db.all<UnitRow>('SELECT id, name, type FROM organization_units WHERE id IN (SELECT organization_unit_id FROM study_groups) ORDER BY name');
  }

  /** Курсы подразделения (null означает «курс не определён») по возрастанию. */
  async courses(unitId: string): Promise<Array<number | null>> {
    const db = await this.db();
    if (db === null) return [];
    const rows = await db.all<{ course: number | null }>('SELECT DISTINCT course FROM study_groups WHERE organization_unit_id = ? ORDER BY course IS NULL, course', [unitId]);
    return rows.map((r) => r.course);
  }

  async groups(unitId: string, course: number | null): Promise<GroupRow[]> {
    const db = await this.db();
    if (db === null) return [];
    const where = course === null ? 'course IS NULL' : 'course = ?';
    const rows = await db.all<GroupDbRow>(
      `SELECT id, organization_unit_id, name, course, subgroup_count FROM study_groups WHERE organization_unit_id = ? AND ${where} ORDER BY name`,
      course === null ? [unitId] : [unitId, course],
    );
    return rows.map(toGroup);
  }

  async group(groupId: string): Promise<GroupRow | null> {
    const db = await this.db();
    if (db === null) return null;
    const row = await db.first<GroupDbRow>('SELECT id, organization_unit_id, name, course, subgroup_count FROM study_groups WHERE id = ?', [groupId]);
    return row === null ? null : toGroup(row);
  }

  /** Все занятия группы: их десятки, поэтому дальше день и неделя отбираются в памяти (src/domain). */
  async entries(groupId: string): Promise<EntryRow[]> {
    const db = await this.db();
    if (db === null) return [];
    const rows = await db.all<{
      id: string;
      group_id: string;
      subject: string;
      lesson_type: string;
      raw_lesson_type: string | null;
      teacher: string | null;
      classroom: string | null;
      day_of_week: number;
      specific_date: string | null;
      start_time: string;
      end_time: string;
      week_type: string;
      subgroup_number: number | null;
      source_id: string;
      original_text: string | null;
    }>(
      `SELECT id, group_id, subject, lesson_type, raw_lesson_type, teacher, classroom, day_of_week, specific_date, start_time, end_time, week_type, subgroup_number, source_id, original_text
       FROM schedule_entries WHERE group_id = ? ORDER BY day_of_week, start_time, subject`,
      [groupId],
    );
    return rows.map((r) => ({
      id: r.id,
      groupId: r.group_id,
      subject: r.subject,
      lessonType: r.lesson_type,
      rawLessonType: r.raw_lesson_type,
      teacher: r.teacher,
      classroom: r.classroom,
      dayOfWeek: r.day_of_week,
      specificDate: r.specific_date,
      startTime: r.start_time,
      endTime: r.end_time,
      weekType: r.week_type,
      subgroupNumber: r.subgroup_number,
      sourceId: r.source_id,
      originalText: r.original_text,
    }));
  }

  async calendarWeeks(): Promise<CalendarWeek[]> {
    const db = await this.db();
    if (db === null) return [];
    const rows = await db.all<{ week_index: number; start_date: string; end_date: string; week_type: string }>('SELECT * FROM academic_calendar ORDER BY week_index');
    return rows.map((r) => ({ index: r.week_index, start: r.start_date, end: r.end_date, weekType: r.week_type as CalendarWeek['weekType'] }));
  }

  /** Начало и конец периода набора («YYYY-MM-DD»). */
  async period(): Promise<{ startDate: string; endDate: string } | null> {
    const db = await this.db();
    if (db === null) return null;
    const row = await db.first<{ start_date: string; end_date: string }>('SELECT start_date, end_date FROM academic_periods ORDER BY start_date LIMIT 1');
    return row === null ? null : { startDate: row.start_date, endDate: row.end_date };
  }

  async close(): Promise<void> {
    if (this.active !== null) {
      const db = this.active.db;
      this.active = null;
      await db.close();
    }
  }
}

interface GroupDbRow {
  id: string;
  organization_unit_id: string;
  name: string;
  course: number | null;
  subgroup_count: number | null;
}

const toGroup = (r: GroupDbRow): GroupRow => ({ id: r.id, unitId: r.organization_unit_id, name: r.name, course: r.course, subgroupCount: r.subgroup_count });
