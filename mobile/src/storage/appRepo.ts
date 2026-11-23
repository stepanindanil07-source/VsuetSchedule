import { APP_DB_DDL, LOCAL_DB_SCHEMA_VERSION, type ParseIssue } from '@core';
import type { Db, DbOpener } from './db';

export const APP_DB_NAME = 'app.db';

export interface SourceMeta {
  sourceId: string;
  url: string;
  etag: string | null;
  lastModified: string | null;
  fileSha256: string | null;
  parserId: string | null;
  parserVersion: string | null;
  lastCheckedAt: string | null;
  lastFetchedAt: string | null;
  entries: number | null;
  skipped: number | null;
}

export type SyncOutcome = 'RUNNING' | 'PUBLISHED' | 'UNCHANGED' | 'UP_TO_DATE' | 'REJECTED' | 'FAILED';

export interface SyncRecord {
  id: number;
  startedAt: string;
  finishedAt: string | null;
  outcome: SyncOutcome;
  datasetVersion: number | null;
  message: string | null;
}

export interface DiagnosticRecord {
  sourceId: string;
  severity: string;
  code: string;
  message: string;
  sheet: string | null;
  range: string | null;
  rawText: string | null;
}

interface SourceMetaRow {
  source_id: string;
  url: string;
  etag: string | null;
  last_modified: string | null;
  file_sha256: string | null;
  parser_id: string | null;
  parser_version: string | null;
  last_checked_at: string | null;
  last_fetched_at: string | null;
  entries: number | null;
  skipped: number | null;
}

/** Сколько последних синхронизаций хранит журнал диагностики. */
const KEEP_SYNCS = 10;

/**
 * Постоянная база приложения (app.db): настройки пользователя, сведения о скачанных файлах, журнал синхронизаций и диагностика
 * разбора. Подмена набора расписания её не затрагивает, поэтому выбор группы переживает обновления.
 */
export class AppRepo {
  private constructor(private readonly db: Db) {}

  static async open(opener: DbOpener): Promise<AppRepo> {
    const db = await opener.open(APP_DB_NAME);
    const version = (await db.first<{ user_version: number }>('PRAGMA user_version'))?.user_version ?? 0;
    if (version > LOCAL_DB_SCHEMA_VERSION) {
      // база создана более новой версией приложения: схему не трогаем, ломаться на неизвестных таблицах нельзя
      throw new Error(`app.db has schema version ${version}, this app understands up to ${LOCAL_DB_SCHEMA_VERSION}`);
    }
    if (version < 1) {
      await db.transaction(async () => {
        for (const statement of APP_DB_DDL) await db.exec(statement);
      });
    }
    return new AppRepo(db);
  }

  close(): Promise<void> {
    return this.db.close();
  }

  // ---- настройки ----

  async getSetting(key: string): Promise<string | null> {
    return (await this.db.first<{ value: string }>('SELECT value FROM user_settings WHERE key = ?', [key]))?.value ?? null;
  }

  async setSetting(key: string, value: string | null): Promise<void> {
    if (value === null) await this.db.run('DELETE FROM user_settings WHERE key = ?', [key]);
    else await this.db.run('INSERT INTO user_settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value', [key, value]);
  }

  async allSettings(): Promise<Record<string, string>> {
    const rows = await this.db.all<{ key: string; value: string }>('SELECT key, value FROM user_settings');
    return Object.fromEntries(rows.map((r) => [r.key, r.value]));
  }

  // ---- скачанные файлы ----

  async getSourceMeta(sourceId: string): Promise<SourceMeta | null> {
    const row = await this.db.first<SourceMetaRow>('SELECT * FROM source_metadata WHERE source_id = ?', [sourceId]);
    return row === null ? null : fromRow(row);
  }

  async listSourceMeta(): Promise<SourceMeta[]> {
    return (await this.db.all<SourceMetaRow>('SELECT * FROM source_metadata ORDER BY source_id')).map(fromRow);
  }

  async upsertSourceMeta(m: SourceMeta): Promise<void> {
    await this.db.run(
      `INSERT INTO source_metadata (source_id, url, etag, last_modified, file_sha256, parser_id, parser_version, last_checked_at, last_fetched_at, entries, skipped)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(source_id) DO UPDATE SET url = excluded.url, etag = excluded.etag, last_modified = excluded.last_modified,
         file_sha256 = excluded.file_sha256, parser_id = excluded.parser_id, parser_version = excluded.parser_version,
         last_checked_at = excluded.last_checked_at, last_fetched_at = excluded.last_fetched_at, entries = excluded.entries, skipped = excluded.skipped`,
      [m.sourceId, m.url, m.etag, m.lastModified, m.fileSha256, m.parserId, m.parserVersion, m.lastCheckedAt, m.lastFetchedAt, m.entries, m.skipped],
    );
  }

  // ---- журнал синхронизаций и диагностика ----

  async beginSync(startedAt: string): Promise<number> {
    await this.db.run("INSERT INTO sync_history (started_at, outcome) VALUES (?, 'RUNNING')", [startedAt]);
    return (await this.db.first<{ id: number }>('SELECT last_insert_rowid() AS id'))!.id;
  }

  async finishSync(id: number, finishedAt: string, outcome: SyncOutcome, datasetVersion: number | null, message: string | null): Promise<void> {
    await this.db.run('UPDATE sync_history SET finished_at = ?, outcome = ?, dataset_version = ?, message = ? WHERE id = ?', [finishedAt, outcome, datasetVersion, message, id]);
    // журнал не растёт бесконечно: диагностика старых синхронизаций удаляется каскадно
    await this.db.run('DELETE FROM sync_history WHERE id NOT IN (SELECT id FROM sync_history ORDER BY id DESC LIMIT ?)', [KEEP_SYNCS]);
  }

  /** Сообщения парсеров одного источника за синхронизацию. */
  async addDiagnostics(syncId: number, sourceId: string, issues: readonly ParseIssue[]): Promise<void> {
    if (issues.length === 0) return;
    await this.db.transaction(() =>
      this.db.runMany(
        'INSERT INTO parse_diagnostics (sync_id, source_id, severity, code, message, sheet, range, raw_text) VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
        issues.map((i) => [syncId, sourceId, i.severity, i.code, i.message, i.sheet, i.range, i.rawText]),
      ),
    );
  }

  async recentSyncs(limit = KEEP_SYNCS): Promise<SyncRecord[]> {
    const rows = await this.db.all<{ id: number; started_at: string; finished_at: string | null; outcome: SyncOutcome; dataset_version: number | null; message: string | null }>(
      'SELECT * FROM sync_history ORDER BY id DESC LIMIT ?',
      [limit],
    );
    return rows.map((r) => ({ id: r.id, startedAt: r.started_at, finishedAt: r.finished_at, outcome: r.outcome, datasetVersion: r.dataset_version, message: r.message }));
  }

  /** Последняя завершённая синхронизация (не считая идущей сейчас), у которой есть сообщения парсеров. */
  async diagnosticsOf(syncId: number): Promise<DiagnosticRecord[]> {
    const rows = await this.db.all<{ source_id: string; severity: string; code: string; message: string; sheet: string | null; range: string | null; raw_text: string | null }>(
      'SELECT source_id, severity, code, message, sheet, range, raw_text FROM parse_diagnostics WHERE sync_id = ? ORDER BY id',
      [syncId],
    );
    return rows.map((r) => ({ sourceId: r.source_id, severity: r.severity, code: r.code, message: r.message, sheet: r.sheet, range: r.range, rawText: r.raw_text }));
  }

  /** Синхронизации, оставшиеся в состоянии RUNNING после аварийного закрытия приложения, помечаются неудачными. */
  async closeAbandonedSyncs(now: string): Promise<void> {
    await this.db.run("UPDATE sync_history SET outcome = 'FAILED', finished_at = ?, message = 'Прервана закрытием приложения' WHERE outcome = 'RUNNING'", [now]);
  }
}

const fromRow = (r: SourceMetaRow): SourceMeta => ({
  sourceId: r.source_id,
  url: r.url,
  etag: r.etag,
  lastModified: r.last_modified,
  fileSha256: r.file_sha256,
  parserId: r.parser_id,
  parserVersion: r.parser_version,
  lastCheckedAt: r.last_checked_at,
  lastFetchedAt: r.last_fetched_at,
  entries: r.entries,
  skipped: r.skipped,
});
