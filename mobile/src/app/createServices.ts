import { createParserRegistry, sha256Bytes, sha256Text } from '../core/runtime';
import { syncConfig } from '../config/appConfig';
import { AppRepo } from '../storage/appRepo';
import { DatasetManager } from '../storage/datasetRepo';
import { expoDbOpener } from '../storage/expoDb';
import { ExpoFileCache } from '../sync/expoFileCache';
import { fetchFile } from '../sync/http';
import { SyncService } from '../sync/syncService';
import type { Services } from '../state/store';

/** Собирает настоящие службы приложения: SQLite, файлы, сеть, парсеры. */
export async function createServices(): Promise<Services> {
  const app = await AppRepo.open(expoDbOpener);
  const datasets = new DatasetManager(expoDbOpener, app);
  const sync = new SyncService({
    config: syncConfig,
    app,
    datasets,
    files: new ExpoFileCache(),
    fetcher: fetchFile,
    parsers: createParserRegistry(),
    sha256Bytes,
    sha256Text,
    now: () => new Date(),
    // отдаём поток интерфейсу, чтобы индикатор прогресса успел перерисоваться
    yieldToUi: () => new Promise<void>((resolve) => setTimeout(resolve, 16)),
  });
  return { app, datasets, sync, now: () => new Date() };
}
