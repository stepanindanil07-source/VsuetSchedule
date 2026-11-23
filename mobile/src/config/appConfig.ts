import { AUTUMN_2026, KNOWN_GAPS, ORGANIZATION_UNITS, SOURCES } from '@core';
import type { SyncConfig } from '../sync/syncService';

/**
 * Настройки синхронизации. Реестр источников и период берутся из ядра (core-ts/src/sources/registry.ts): адреса файлов и семестр
 * описаны только там. Смена семестра или адреса файла это правка реестра и новый выпуск приложения (удалённой конфигурации нет).
 */
export const syncConfig: SyncConfig = {
  sources: SOURCES,
  units: ORGANIZATION_UNITS,
  period: AUTUMN_2026,
  periods: [AUTUMN_2026],
  knownGaps: KNOWN_GAPS,
  // автоматически не чаще раза в 6 часов: файлы меняются нечасто, а сайт общий для всех студентов
  minCheckIntervalMs: 6 * 60 * 60 * 1000,
  requestTimeoutMs: 30_000,
  attempts: 3,
  retryBaseDelayMs: 1_500,
  concurrency: 3,
};
