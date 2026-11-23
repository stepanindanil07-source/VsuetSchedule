import * as SQLite from 'expo-sqlite';
import { Mutex, type Db, type DbOpener, type SqlValue } from './db';

/** Реализация интерфейса Db поверх expo-sqlite (асинхронный интерфейс: работа с базой идёт не на потоке JavaScript). */
class ExpoDb implements Db {
  private readonly mutex = new Mutex();
  constructor(private readonly db: SQLite.SQLiteDatabase) {}

  exec(sql: string): Promise<void> {
    return this.db.execAsync(sql);
  }

  async run(sql: string, params: readonly SqlValue[] = []): Promise<void> {
    await this.db.runAsync(sql, [...params]);
  }

  all<T>(sql: string, params: readonly SqlValue[] = []): Promise<T[]> {
    return this.db.getAllAsync<T>(sql, [...params]);
  }

  async first<T>(sql: string, params: readonly SqlValue[] = []): Promise<T | null> {
    return (await this.db.getFirstAsync<T>(sql, [...params])) ?? null;
  }

  async runMany(sql: string, rows: ReadonlyArray<readonly SqlValue[]>): Promise<void> {
    const statement = await this.db.prepareAsync(sql);
    try {
      for (const row of rows) await statement.executeAsync([...row]);
    } finally {
      await statement.finalizeAsync();
    }
  }

  transaction<T>(action: () => Promise<T>): Promise<T> {
    return this.mutex.run(async () => {
      await this.db.execAsync('BEGIN');
      try {
        const result = await action();
        await this.db.execAsync('COMMIT');
        return result;
      } catch (error) {
        try {
          await this.db.execAsync('ROLLBACK');
        } catch {
          // откат не удался (соединение уже сброшено): первичная ошибка важнее
        }
        throw error;
      }
    });
  }

  close(): Promise<void> {
    return this.db.closeAsync();
  }
}

export const expoDbOpener: DbOpener = {
  async open(name) {
    const db = await SQLite.openDatabaseAsync(name);
    await db.execAsync('PRAGMA foreign_keys = ON');
    return new ExpoDb(db);
  },
  async remove(name) {
    try {
      await SQLite.deleteDatabaseAsync(name);
    } catch {
      // файла нет: удалять нечего
    }
  },
};
