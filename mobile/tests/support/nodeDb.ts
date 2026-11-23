import { mkdtempSync, rmSync, unlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync, type SQLInputValue } from 'node:sqlite';
import { Mutex, type Db, type DbOpener, type SqlValue } from '../../src/storage/db';

/** Реализация Db на встроенном в Node SQLite: те же запросы, что на телефоне, без телефона. */
class NodeDb implements Db {
  private readonly mutex = new Mutex();
  constructor(private readonly db: DatabaseSync) {}
  async exec(sql: string): Promise<void> {
    this.db.exec(sql);
  }
  async run(sql: string, params: readonly SqlValue[] = []): Promise<void> {
    this.db.prepare(sql).run(...(params as SQLInputValue[]));
  }
  async all<T>(sql: string, params: readonly SqlValue[] = []): Promise<T[]> {
    return this.db.prepare(sql).all(...(params as SQLInputValue[])) as T[];
  }
  async first<T>(sql: string, params: readonly SqlValue[] = []): Promise<T | null> {
    return (this.db.prepare(sql).get(...(params as SQLInputValue[])) as T | undefined) ?? null;
  }
  async runMany(sql: string, rows: ReadonlyArray<readonly SqlValue[]>): Promise<void> {
    const statement = this.db.prepare(sql);
    for (const row of rows) statement.run(...(row as SQLInputValue[]));
  }
  transaction<T>(action: () => Promise<T>): Promise<T> {
    return this.mutex.run(async () => {
      this.db.exec('BEGIN');
      try {
        const result = await action();
        this.db.exec('COMMIT');
        return result;
      } catch (error) {
        this.db.exec('ROLLBACK');
        throw error;
      }
    });
  }
  async close(): Promise<void> {
    this.db.close();
  }
}

/** Базы-файлы во временном каталоге (как на устройстве: закрыли и снова открыли, данные на месте). */
export class NodeDbOpener implements DbOpener {
  readonly dir = mkdtempSync(join(tmpdir(), 'vsuet-db-'));
  /** Имена баз, в которые запрещено писать: имитация нехватки места при сборке нового набора. */
  failOnOpen = new Set<string>();

  async open(name: string): Promise<Db> {
    if (this.failOnOpen.has(name)) throw new Error(`disk full (${name})`);
    const db = new DatabaseSync(join(this.dir, name));
    db.exec('PRAGMA foreign_keys = ON');
    return new NodeDb(db);
  }
  async remove(name: string): Promise<void> {
    for (const suffix of ['', '-journal', '-wal', '-shm']) {
      try {
        unlinkSync(join(this.dir, name + suffix));
      } catch {
        // файла нет
      }
    }
  }
  dispose(): void {
    rmSync(this.dir, { recursive: true, force: true });
  }
}
