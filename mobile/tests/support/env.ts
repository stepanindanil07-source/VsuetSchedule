import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { SOURCES, type ParserRegistry } from '@core';
import { createParserRegistry, sha256Bytes, sha256Text } from '../../src/core/runtime';
import { syncConfig } from '../../src/config/appConfig';
import { AppRepo } from '../../src/storage/appRepo';
import { DatasetManager } from '../../src/storage/datasetRepo';
import { MemoryFileCache } from '../../src/sync/fileCache';
import { PermanentFetchError, RetryableFetchError, type ConditionalRequest, type FetchResult } from '../../src/sync/http';
import { SyncService, type SyncConfig } from '../../src/sync/syncService';
import { NodeDbOpener } from './nodeDb';

const FIXTURES = join(__dirname, '../../../core-ts/fixtures/files');

/** Какой файл из fixtures соответствует какому источнику реестра. */
export const FILE_OF: Record<string, string> = {
  uits: 'uits.xlsx',
  itf: 'itf.xlsx',
  eht: 'eht.xlsx',
  tf: 'tf.xlsx',
  eiu: 'eiu.xlsx',
  'fspo-1': 'fspo-1_kurs.xls',
  'fspo-234': 'fspo-2-3-4_kurs.xls',
  'zao-5': 'zao-5_kurs.xlsx',
};

export const fixtureBytes = (sourceId: string): Uint8Array => new Uint8Array(readFileSync(join(FIXTURES, FILE_OF[sourceId])));

interface Served {
  bytes: Uint8Array;
  etag: string;
  lastModified: string;
}

/** Поддельный сайт университета: отдаёт файлы, понимает условные запросы, может ломаться. */
export class FakeSite {
  readonly files = new Map<string, Served>();
  /** Журнал запросов: адрес и были ли условные заголовки. */
  readonly requests: Array<{ url: string; conditional: boolean; result: string }> = [];
  /** Что вернуть вместо файла для адреса: ошибка сети, 404 или «страница ошибки». */
  readonly broken = new Map<string, 'NETWORK' | 'NOT_FOUND' | 'HTML'>();
  allDown = false;
  private version = 1;

  constructor() {
    for (const s of SOURCES) this.files.set(s.url, { bytes: fixtureBytes(s.id), etag: `"${s.id}-1"`, lastModified: 'Mon, 01 Sep 2026 08:00:00 GMT' });
  }

  /** Меняет файл на сайте (новое содержимое и новый ETag). */
  replace(sourceId: string, bytes: Uint8Array): void {
    const url = SOURCES.find((s) => s.id === sourceId)!.url;
    this.version++;
    this.files.set(url, { bytes, etag: `"${sourceId}-${this.version}"`, lastModified: `Tue, 0${this.version} Sep 2026 08:00:00 GMT` });
  }

  fetcher = async (request: ConditionalRequest): Promise<FetchResult> => {
    const conditional = request.etag !== null || request.lastModified !== null;
    const log = (result: string): void => void this.requests.push({ url: request.url, conditional, result });
    if (this.allDown || this.broken.get(request.url) === 'NETWORK') {
      log('network error');
      throw new RetryableFetchError('Нет соединения');
    }
    if (this.broken.get(request.url) === 'NOT_FOUND') {
      log('404');
      throw new PermanentFetchError('Сайт ответил 404');
    }
    if (this.broken.get(request.url) === 'HTML') {
      log('html');
      return { status: 'OK', bytes: new TextEncoder().encode('<html><body>Ошибка сервера</body></html>'), etag: null, lastModified: null };
    }
    const served = this.files.get(request.url)!;
    if (request.etag === served.etag) {
      log('304');
      return { status: 'NOT_MODIFIED' };
    }
    log('200');
    return { status: 'OK', bytes: served.bytes, etag: served.etag, lastModified: served.lastModified };
  };
}

export interface Env {
  opener: NodeDbOpener;
  app: AppRepo;
  datasets: DatasetManager;
  files: MemoryFileCache;
  site: FakeSite;
  service: SyncService;
  clock: { now: Date };
  dispose(): Promise<void>;
}

export async function createEnv(configOverride: Partial<SyncConfig> = {}, parsers: ParserRegistry = createParserRegistry()): Promise<Env> {
  const opener = new NodeDbOpener();
  const app = await AppRepo.open(opener);
  const datasets = new DatasetManager(opener, app);
  const files = new MemoryFileCache();
  const site = new FakeSite();
  const clock = { now: new Date('2026-11-23T10:00:00Z') };
  const service = new SyncService({
    config: { ...syncConfig, attempts: 2, retryBaseDelayMs: 1, ...configOverride },
    app,
    datasets,
    files,
    fetcher: site.fetcher,
    parsers,
    sha256Bytes,
    sha256Text,
    now: () => clock.now,
    sleep: async () => undefined,
  });
  return {
    opener,
    app,
    datasets,
    files,
    site,
    service,
    clock,
    async dispose() {
      await datasets.close();
      await app.close();
      opener.dispose();
    },
  };
}
