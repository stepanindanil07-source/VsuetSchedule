import {
  contextFromPeriodStart,
  publishDataset,
  SCHEMA_VERSION,
  ScheduleParseError,
  UNKNOWN_CONTEXT,
  type AcademicPeriod,
  type BuildProblem,
  type KnownGap,
  type OrganizationUnit,
  type ParseIssue,
  type ParserRegistry,
  type ScheduleSource,
  type SourceImport,
} from '@core';
import type { AppRepo, SourceMeta, SyncOutcome } from '../storage/appRepo';
import type { DatasetManager } from '../storage/datasetRepo';
import { looksLikeExcel, PermanentFetchError, withRetries, type Fetcher } from './http';
import type { FileCache } from './fileCache';

/**
 * Синхронизация: проверить файлы на сайте, при изменениях разобрать, собрать набор и подменить его целиком.
 *
 * Правила (по ТЗ и по опыту backend):
 *  - Файл не заменяет прежнюю копию, пока не разобран. Набор не заменяет прежний, пока не собран ВЕСЬ и не прошёл проверки.
 *  - Любая ошибка (нет сети, сайт вернул не Excel, парсер не справился, набор подозрительно уменьшился) оставляет прежнее
 *    расписание рабочим и записывается в журнал.
 *  - Без изменений файлов и парсеров ничего не разбирается: ответ 304 или та же контрольная сумма, та же версия парсера.
 */

export interface SyncConfig {
  sources: readonly ScheduleSource[];
  units: readonly OrganizationUnit[];
  period: AcademicPeriod;
  periods: readonly AcademicPeriod[];
  knownGaps: readonly KnownGap[];
  /** Не ходить в сеть чаще этого интервала при автоматической проверке; ручное «Обновить» интервал не учитывает. */
  minCheckIntervalMs: number;
  requestTimeoutMs: number;
  attempts: number;
  retryBaseDelayMs: number;
  concurrency: number;
}

export interface SyncDeps {
  config: SyncConfig;
  app: AppRepo;
  datasets: DatasetManager;
  files: FileCache;
  fetcher: Fetcher;
  parsers: ParserRegistry;
  sha256Bytes: (bytes: Uint8Array) => string;
  sha256Text: (text: string) => string;
  now: () => Date;
  /** Пауза, чтобы интерфейс успел перерисоваться между тяжёлыми шагами; в тестах пустая. */
  yieldToUi?: () => Promise<void>;
  sleep?: (ms: number) => Promise<void>;
  /** Монотонные миллисекунды для замера этапов; по умолчанию Date.now. */
  clock?: () => number;
}

export type SyncStage = 'CHECKING' | 'PARSING' | 'BUILDING' | 'SAVING';

export interface SyncProgress {
  stage: SyncStage;
  /** Сколько шагов этапа выполнено и сколько всего. */
  completed: number;
  total: number;
  sourceName?: string;
}

export type SourceStatus = 'FETCHED' | 'NOT_MODIFIED' | 'OFFLINE_CACHED' | 'FAILED';

export interface SourceReport {
  sourceId: string;
  name: string;
  status: SourceStatus;
  /** Файл новый или изменился относительно набора, лежащего в базе. */
  changed: boolean;
  entries: number | null;
  /** Что пошло не так, человеческим языком. */
  message: string | null;
}

export type SyncResult = {
  outcome: Exclude<SyncOutcome, 'RUNNING'>;
  datasetVersion: number | null;
  /** Одна строка для пользователя. */
  message: string;
  sources: SourceReport[];
  problems: BuildProblem[];
  /** Хотя бы один файл взят из сохранённой копии, потому что сайт недоступен. */
  usedOfflineCopies: boolean;
  /** Сети не касались: недавняя проверка. */
  skipped: boolean;
  /** Сколько миллисекунд заняли этапы (загрузка и проверка, разбор, сборка и сохранение). */
  timings: { downloadMs: number; parseMs: number; buildMs: number; totalMs: number } | null;
};

export interface SyncOptions {
  /** Ручное обновление: проверить сайт, даже если недавно проверяли. */
  force?: boolean;
  onProgress?: (progress: SyncProgress) => void;
}

const LAST_CHECK_KEY = 'lastSuccessfulCheckAt';

interface Acquired {
  source: ScheduleSource;
  bytes: Uint8Array | null;
  /** Новые заголовки, если файл только что скачан. */
  fresh: { etag: string | null; lastModified: string | null } | null;
  status: SourceStatus;
  message: string | null;
}

export class SyncService {
  private running: Promise<SyncResult> | null = null;
  private marks: { start: number; downloaded: number | null; parsed: number | null; built: number | null } = { start: 0, downloaded: null, parsed: null, built: null };

  constructor(private readonly deps: SyncDeps) {}

  /** Одновременно идёт одна синхронизация: повторный вызов получает результат идущей. */
  run(options: SyncOptions = {}): Promise<SyncResult> {
    if (this.running === null) {
      this.running = this.execute(options).finally(() => {
        this.running = null;
      });
    }
    return this.running;
  }

  get isRunning(): boolean {
    return this.running !== null;
  }

  private async execute(options: SyncOptions): Promise<SyncResult> {
    const { app, datasets, config } = this.deps;
    const enabled = config.sources.filter((s) => s.enabled);

    if (options.force !== true && (await this.checkedRecently()) && (await this.datasetMatchesRegistry(enabled))) {
      return { outcome: 'UP_TO_DATE', datasetVersion: (await datasets.info())?.datasetVersion ?? null, message: 'Расписание проверено недавно', sources: [], problems: [], usedOfflineCopies: false, skipped: true, timings: null };
    }

    const syncId = await app.beginSync(this.nowIso());
    let result: SyncResult;
    try {
      result = await this.syncSources(enabled, syncId, options);
    } catch (error) {
      result = { outcome: 'FAILED', datasetVersion: null, message: `Синхронизация не удалась: ${(error as Error).message}`, sources: [], problems: [], usedOfflineCopies: false, skipped: false, timings: null };
    }
    result = this.withTimings(result);
    try {
      await app.finishSync(syncId, this.nowIso(), result.outcome, result.datasetVersion, describe(result));
    } catch {
      // журнал вспомогателен: его сбой не должен менять результат синхронизации
    }
    return result;
  }

  private async syncSources(enabled: readonly ScheduleSource[], syncId: number, options: SyncOptions): Promise<SyncResult> {
    const { app, datasets, config, parsers } = this.deps;
    const progress = options.onProgress ?? (() => undefined);

    const clock = this.deps.clock ?? Date.now;
    this.marks = { start: clock(), downloaded: null, parsed: null, built: null };
    // 1. Проверка и загрузка (сеть)
    let checked = 0;
    progress({ stage: 'CHECKING', completed: 0, total: enabled.length });
    const acquired = await mapPool(enabled, config.concurrency, async (source) => {
      const a = await this.acquire(source);
      progress({ stage: 'CHECKING', completed: ++checked, total: enabled.length, sourceName: source.name });
      return a;
    });
    this.marks.downloaded = clock();
    const contacted = acquired.some((a) => a.status === 'FETCHED' || a.status === 'NOT_MODIFIED');
    const usedOffline = acquired.some((a) => a.status === 'OFFLINE_CACHED');

    // 2. Что изменилось относительно набора в базе
    const previous = await datasets.info();
    const shaOf = new Map<string, string>();
    for (const a of acquired) if (a.bytes !== null) shaOf.set(a.source.id, this.deps.sha256Bytes(a.bytes));
    const unchangedSources = new Set<string>();
    if (previous !== null && previous.manifest.schemaVersion === SCHEMA_VERSION && previous.manifest.academicPeriod.id === config.period.id) {
      for (const a of acquired) {
        const inManifest = previous.manifest.sources.find((s) => s.sourceId === a.source.id);
        const sha = shaOf.get(a.source.id);
        if (inManifest && sha !== undefined && inManifest.fileSha256 === sha && inManifest.parserVersion === parsers.get(a.source.parserId).parserVersion) {
          unchangedSources.add(a.source.id);
        }
      }
    }
    const sameSet = previous !== null && previous.manifest.sources.length === enabled.length && unchangedSources.size === enabled.length;
    const reports: SourceReport[] = acquired.map((a) => ({
      sourceId: a.source.id,
      name: a.source.name,
      status: a.status,
      changed: !unchangedSources.has(a.source.id),
      entries: previous?.manifest.sources.find((s) => s.sourceId === a.source.id)?.entries ?? null,
      message: a.message,
    }));

    if (sameSet) {
      await this.touchMeta(acquired, shaOf, null);
      if (contacted) await app.setSetting(LAST_CHECK_KEY, this.nowIso());
      return {
        outcome: 'UP_TO_DATE',
        datasetVersion: previous.datasetVersion,
        message: usedOffline ? 'Сайт недоступен, показано сохранённое расписание' : 'Расписание актуально',
        sources: reports,
        problems: [],
        usedOfflineCopies: usedOffline,
        skipped: false,
        timings: null,
      };
    }

    // 3. Разбор (процессор): по одному файлу, между файлами интерфейс получает возможность перерисоваться
    const imports: SourceImport[] = [];
    const parsedMeta = new Map<string, { entries: number; skipped: number }>();
    let parsed = 0;
    for (const a of acquired) {
      progress({ stage: 'PARSING', completed: parsed, total: enabled.length, sourceName: a.source.name });
      await this.deps.yieldToUi?.();
      const report = reports.find((r) => r.sourceId === a.source.id)!;
      if (a.bytes === null) {
        imports.push({ source: a.source, outcome: { status: 'FAILED', error: a.message ?? 'Файл недоступен' } });
        report.message = report.message ?? 'Файл недоступен';
      } else {
        const outcome = this.parse(a.source, a.bytes, shaOf.get(a.source.id)!);
        imports.push({ source: a.source, outcome });
        if (outcome.status === 'OK') {
          report.entries = outcome.parsed.entries.length;
          parsedMeta.set(a.source.id, { entries: outcome.parsed.entries.length, skipped: outcome.parsed.diagnostics.skipped });
          await app.addDiagnostics(syncId, a.source.id, outcome.parsed.diagnostics.issues);
        } else {
          report.status = 'FAILED';
          report.message = outcome.error;
          await app.addDiagnostics(syncId, a.source.id, [{ severity: 'ERROR', code: 'SOURCE_FAILED', message: outcome.error, sheet: null, range: null, rawText: null } satisfies ParseIssue]);
        }
      }
      parsed++;
    }

    this.marks.parsed = clock();
    // 4. Сборка набора и подмена
    progress({ stage: 'BUILDING', completed: 0, total: 1 });
    await this.deps.yieldToUi?.();
    const outcome = await publishDataset(
      {
        sources: config.sources,
        imports,
        units: config.units,
        period: config.period,
        periods: config.periods,
        knownGaps: config.knownGaps,
        now: this.nowIso().replace(/\.\d{3}Z$/, 'Z'),
        sha256: this.deps.sha256Text,
      },
      datasets,
    );
    progress({ stage: 'SAVING', completed: 1, total: 1 });
    this.marks.built = clock();

    // 5. Файлы, которые разобрались, становятся рабочими копиями (даже если весь набор отклонён по другой причине)
    await this.touchMeta(acquired, shaOf, parsedMeta);
    if (contacted) await app.setSetting(LAST_CHECK_KEY, this.nowIso());

    if (outcome.status === 'PUBLISHED') {
      const v = outcome.dataset.manifest.datasetVersion;
      return { outcome: 'PUBLISHED', datasetVersion: v, message: v === 1 ? 'Расписание загружено' : `Расписание обновлено (версия ${v})`, sources: reports, problems: outcome.dataset.manifest.warnings, usedOfflineCopies: usedOffline, skipped: false, timings: null };
    }
    if (outcome.status === 'UNCHANGED') {
      return { outcome: 'UNCHANGED', datasetVersion: outcome.datasetVersion, message: 'Файлы изменились, но расписание осталось прежним', sources: reports, problems: outcome.warnings, usedOfflineCopies: usedOffline, skipped: false, timings: null };
    }
    const kept = outcome.keptDatasetVersion;
    return {
      outcome: 'REJECTED',
      datasetVersion: kept,
      message: kept === null ? 'Не удалось собрать расписание' : 'Новое расписание не принято, показано прежнее',
      sources: reports,
      problems: outcome.reasons,
      usedOfflineCopies: usedOffline,
      skipped: false,
      timings: null,
    };
  }

  /** Берёт файл с сайта (условный запрос) или из сохранённой копии. */
  private async acquire(source: ScheduleSource): Promise<Acquired> {
    const { app, files, fetcher, config } = this.deps;
    const meta = await app.getSourceMeta(source.id);
    const cached = await files.read(source.id);
    try {
      const result = await withRetries(
        () =>
          fetcher({
            url: source.url,
            etag: cached !== null ? (meta?.etag ?? null) : null,
            lastModified: cached !== null ? (meta?.lastModified ?? null) : null,
            timeoutMs: config.requestTimeoutMs,
          }),
        { attempts: config.attempts, baseDelayMs: config.retryBaseDelayMs, sleep: this.deps.sleep },
      );
      if (result.status === 'NOT_MODIFIED') {
        if (cached === null) throw new PermanentFetchError('Сайт ответил «не изменялось», но сохранённой копии нет');
        return { source, bytes: cached, fresh: null, status: 'NOT_MODIFIED', message: null };
      }
      if (!looksLikeExcel(result.bytes, source.format)) throw new PermanentFetchError('Сайт вернул не файл Excel (возможно, страницу с ошибкой)');
      return { source, bytes: result.bytes, fresh: { etag: result.etag, lastModified: result.lastModified }, status: 'FETCHED', message: null };
    } catch (error) {
      const reason = (error as Error).message;
      if (cached !== null) return { source, bytes: cached, fresh: null, status: 'OFFLINE_CACHED', message: `${reason}; используется сохранённая копия` };
      return { source, bytes: null, fresh: null, status: 'FAILED', message: reason };
    }
  }

  private parse(source: ScheduleSource, bytes: Uint8Array, sha: string): SourceImport['outcome'] {
    const { parsers, config } = this.deps;
    try {
      const parser = parsers.get(source.parserId);
      const period = source.periodId === null ? null : config.periods.find((p) => p.id === source.periodId);
      if (source.periodId !== null && !period) throw new Error(`Источник привязан к неизвестному периоду ${source.periodId}`);
      const context = period ? contextFromPeriodStart(period.startDate) : UNKNOWN_CONTEXT;
      const parsed = parser.parse({ bytes, context });
      return { status: 'OK', parsed, fileSha256: sha, fetchedAt: this.nowIso().replace(/\.\d{3}Z$/, 'Z') };
    } catch (error) {
      const message = error instanceof ScheduleParseError ? error.message : `Парсер не справился: ${(error as Error).message}`;
      return { status: 'FAILED', error: message };
    }
  }

  /** Сохраняет рабочие копии файлов и сведения о них. `parsed` null: ничего не разбиралось, обновляется только время проверки. */
  private async touchMeta(acquired: readonly Acquired[], shaOf: ReadonlyMap<string, string>, parsed: ReadonlyMap<string, { entries: number; skipped: number }> | null): Promise<void> {
    const { app, files, parsers } = this.deps;
    const now = this.nowIso();
    for (const a of acquired) {
      if (a.bytes === null) continue;
      const old = await app.getSourceMeta(a.source.id);
      const parserVersion = parsers.get(a.source.parserId).parserVersion;
      if (a.fresh !== null) {
        // новая копия сохраняется только если файл разобрался
        const stats = parsed?.get(a.source.id);
        if (parsed !== null && stats === undefined) continue;
        await files.write(a.source.id, a.bytes);
        const meta: SourceMeta = {
          sourceId: a.source.id,
          url: a.source.url,
          etag: a.fresh.etag,
          lastModified: a.fresh.lastModified,
          fileSha256: shaOf.get(a.source.id) ?? null,
          parserId: a.source.parserId,
          parserVersion,
          lastCheckedAt: now,
          lastFetchedAt: now,
          entries: stats?.entries ?? old?.entries ?? null,
          skipped: stats?.skipped ?? old?.skipped ?? null,
        };
        await app.upsertSourceMeta(meta);
      } else if (a.status === 'NOT_MODIFIED' && old !== null) {
        const stats = parsed?.get(a.source.id);
        await app.upsertSourceMeta({ ...old, lastCheckedAt: now, parserVersion: stats ? parserVersion : old.parserVersion, entries: stats?.entries ?? old.entries, skipped: stats?.skipped ?? old.skipped });
      }
    }
  }

  private async checkedRecently(): Promise<boolean> {
    const last = await this.deps.app.getSetting(LAST_CHECK_KEY);
    if (last === null) return false;
    const age = this.deps.now().getTime() - Date.parse(last);
    return Number.isFinite(age) && age >= 0 && age < this.deps.config.minCheckIntervalMs;
  }

  /** Набор в базе построен из тех же источников и периода, что и сейчас в реестре приложения (после обновления приложения это может не так). */
  private async datasetMatchesRegistry(enabled: readonly ScheduleSource[]): Promise<boolean> {
    const info = await this.deps.datasets.info();
    if (info === null || info.manifest.schemaVersion !== SCHEMA_VERSION || info.manifest.academicPeriod.id !== this.deps.config.period.id) return false;
    const ids = new Set(info.manifest.sources.map((s) => s.sourceId));
    if (ids.size !== enabled.length || !enabled.every((s) => ids.has(s.id))) return false;
    return enabled.every((s) => info.manifest.sources.find((m) => m.sourceId === s.id)?.parserVersion === this.deps.parsers.get(s.parserId).parserVersion);
  }

  /** Добавляет к результату длительность этапов (загрузка, разбор, сборка с сохранением), если синхронизация дошла до сборки. */
  private withTimings(result: SyncResult): SyncResult {
    const clock = this.deps.clock ?? Date.now;
    const m = this.marks;
    const end = clock();
    if (m.downloaded === null) return result;
    const parsedAt = m.parsed ?? end;
    return { ...result, timings: { downloadMs: m.downloaded - m.start, parseMs: parsedAt - m.downloaded, buildMs: (m.built ?? end) - parsedAt, totalMs: end - m.start } };
  }

  private nowIso(): string {
    return this.deps.now().toISOString();
  }
}

/** Строка для журнала: сообщение и, если есть, время этапов. */
function describe(result: SyncResult): string {
  const t = result.timings;
  if (t === null) return result.message;
  const sec = (ms: number): string => (ms / 1000).toFixed(1);
  return `${result.message} (${sec(t.totalMs)} с: проверка и загрузка ${sec(t.downloadMs)}, разбор ${sec(t.parseMs)}, сборка и сохранение ${sec(t.buildMs)})`;
}

/** Выполняет действие над элементами не более чем `limit` одновременно, сохраняя порядок результатов. */
async function mapPool<T, R>(items: readonly T[], limit: number, action: (item: T) => Promise<R>): Promise<R[]> {
  const results = new Array<R>(items.length);
  let next = 0;
  const worker = async (): Promise<void> => {
    while (next < items.length) {
      const index = next++;
      results[index] = await action(items[index]);
    }
  };
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
  return results;
}
