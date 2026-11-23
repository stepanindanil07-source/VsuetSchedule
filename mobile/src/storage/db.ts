/**
 * Узкий интерфейс к SQLite. Приложение и тесты работают с ним, а не с expo-sqlite напрямую: в приложении его реализует expo-sqlite
 * (expoDb.ts), в тестах Jest встроенный в Node модуль node:sqlite (tests/support/nodeDb.ts), так что запросы и миграции
 * проверяются на настоящей SQLite без телефона.
 */

export type SqlValue = string | number | null;

export interface Db {
  /** Несколько операторов без параметров (создание таблиц, PRAGMA). */
  exec(sql: string): Promise<void>;
  run(sql: string, params?: readonly SqlValue[]): Promise<void>;
  all<T>(sql: string, params?: readonly SqlValue[]): Promise<T[]>;
  first<T>(sql: string, params?: readonly SqlValue[]): Promise<T | null>;
  /** Один оператор с множеством наборов параметров (вставка пачкой). */
  runMany(sql: string, rows: ReadonlyArray<readonly SqlValue[]>): Promise<void>;
  /** Выполняет действие в транзакции: успех фиксирует, исключение откатывает. Транзакции одной базы идут строго по очереди. */
  transaction<T>(action: () => Promise<T>): Promise<T>;
  close(): Promise<void>;
}

/** Открывает и удаляет базы по имени (имя файла, без пути). */
export interface DbOpener {
  open(name: string): Promise<Db>;
  /** Удаляет файл базы, если он есть. Базу перед этим нужно закрыть. */
  remove(name: string): Promise<void>;
}

/** Очередь для транзакций: следующая начинается, когда закончилась предыдущая. */
export class Mutex {
  private tail: Promise<unknown> = Promise.resolve();
  run<T>(action: () => Promise<T>): Promise<T> {
    const result = this.tail.then(action, action);
    this.tail = result.catch(() => undefined);
    return result;
  }
}
