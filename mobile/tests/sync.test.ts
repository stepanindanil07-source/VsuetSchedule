import * as XLSX from '../../core-ts/node_modules/xlsx';
import { ParserRegistry, SOURCES, type ScheduleParser } from '@core';
import { syncConfig } from '../src/config/appConfig';
import { createParserRegistry, sha256Bytes, sha256Text } from '../src/core/runtime';
import { SyncService } from '../src/sync/syncService';
import { createEnv, fixtureBytes, type Env } from './support/env';

/** Синхронизация целиком: настоящие парсеры, настоящая SQLite (node:sqlite), поддельный сайт. */

let env: Env;
afterEach(async () => {
  await env?.dispose();
});

/** Файл ТФ с изменённым названием одного предмета: настоящий Excel, который разбирается так же, но содержимое другое. */
function modifiedTf(): Uint8Array {
  const wb = XLSX.read(fixtureBytes('tf'), { type: 'array', cellStyles: true, cellText: true });
  let changed = false;
  for (const name of wb.SheetNames) {
    for (const [ref, cell] of Object.entries(wb.Sheets[name])) {
      if (!changed && !ref.startsWith('!') && (cell as XLSX.CellObject).t === 's' && /\.\S/.test(String((cell as XLSX.CellObject).v)) && String((cell as XLSX.CellObject).v).includes('\n')) {
        (cell as XLSX.CellObject).v = String((cell as XLSX.CellObject).v).replace(/^([^\n]*)/, '$1 (изм.)');
        (cell as XLSX.CellObject).w = undefined;
        changed = true;
      }
    }
  }
  if (!changed) throw new Error('в файле не нашлось занятия для изменения');
  return new Uint8Array(XLSX.write(wb, { type: 'array', bookType: 'xlsx' }));
}

describe('первая синхронизация', () => {
  it('скачивает 8 файлов, разбирает, публикует набор версии 1 и сохраняет файлы', async () => {
    env = await createEnv();
    const progress: string[] = [];
    const result = await env.service.run({ force: true, onProgress: (p) => progress.push(p.stage) });

    expect(result.outcome).toBe('PUBLISHED');
    expect(result.datasetVersion).toBe(1);
    expect(result.sources).toHaveLength(8);
    expect(result.sources.every((s) => s.status === 'FETCHED')).toBe(true);
    expect(new Set(progress)).toEqual(new Set(['CHECKING', 'PARSING', 'BUILDING', 'SAVING']));
    expect(env.site.requests.every((r) => !r.conditional)).toBe(true);
    expect(env.files.files.size).toBe(8);

    const info = await env.datasets.info();
    expect(info?.datasetVersion).toBe(1);
    expect(info?.manifest.counts.groups).toBe(300);
    expect(info?.manifest.counts.scheduleEntries).toBe(7285);
    expect(info?.coverageComplete).toBe(false); // 7 файлов университета не подключены, и это честно отражено
    expect(info?.calendarProvided).toBe(true);

    const metas = await env.app.listSourceMeta();
    expect(metas).toHaveLength(8);
    expect(metas.every((m) => m.etag !== null && m.fileSha256 !== null && m.parserVersion !== null)).toBe(true);
    const history = await env.app.recentSyncs();
    expect(history[0]).toMatchObject({ outcome: 'PUBLISHED', datasetVersion: 1 });
  });

  it('первая синхронизация без сети: набора нет, причина понятна; после появления сети всё загружается', async () => {
    env = await createEnv();
    env.site.allDown = true;
    const failed = await env.service.run({ force: true });
    expect(failed.outcome).toBe('REJECTED');
    expect(failed.datasetVersion).toBeNull();
    expect(failed.sources.every((s) => s.status === 'FAILED')).toBe(true);
    expect(await env.datasets.info()).toBeNull();

    env.site.allDown = false;
    const ok = await env.service.run({ force: true });
    expect(ok.outcome).toBe('PUBLISHED');
  });

  it('один файл недоступен при первой загрузке: набор не публикуется (неполный набор не показывается как полный)', async () => {
    env = await createEnv();
    env.site.broken.set(SOURCES.find((s) => s.id === 'eht')!.url, 'NOT_FOUND');
    const result = await env.service.run({ force: true });
    expect(result.outcome).toBe('REJECTED');
    expect(result.sources.find((s) => s.sourceId === 'eht')).toMatchObject({ status: 'FAILED', message: 'Сайт ответил 404' });
    expect(await env.datasets.info()).toBeNull();
  });

  it('сайт вернул страницу ошибки вместо Excel: она не принимается и не попадает в копии файлов', async () => {
    env = await createEnv();
    const url = SOURCES.find((s) => s.id === 'itf')!.url;
    env.site.broken.set(url, 'HTML');
    const result = await env.service.run({ force: true });
    expect(result.outcome).toBe('REJECTED');
    expect(result.sources.find((s) => s.sourceId === 'itf')?.message).toContain('не файл Excel');
    expect(env.files.files.has('itf')).toBe(false);
  });
});

describe('повторные проверки', () => {
  beforeEach(async () => {
    env = await createEnv();
    await env.service.run({ force: true });
    env.site.requests.length = 0;
  });

  it('ручная проверка: условные запросы, ответ 304, ничего не разбирается, версия прежняя', async () => {
    const result = await env.service.run({ force: true });
    expect(result.outcome).toBe('UP_TO_DATE');
    expect(result.datasetVersion).toBe(1);
    expect(env.site.requests).toHaveLength(8);
    expect(env.site.requests.every((r) => r.conditional && r.result === '304')).toBe(true);
    expect((await env.datasets.info())?.datasetVersion).toBe(1);
  });

  it('автоматическая проверка в пределах интервала не ходит в сеть', async () => {
    env.clock.now = new Date(env.clock.now.getTime() + 60 * 60 * 1000);
    const result = await env.service.run();
    expect(result).toMatchObject({ outcome: 'UP_TO_DATE', skipped: true });
    expect(env.site.requests).toHaveLength(0);
  });

  it('автоматическая проверка после интервала идёт в сеть', async () => {
    env.clock.now = new Date(env.clock.now.getTime() + 7 * 60 * 60 * 1000);
    const result = await env.service.run();
    expect(result.skipped).toBe(false);
    expect(env.site.requests).toHaveLength(8);
  });

  it('изменился один файл: качается только он, набор обновляется до версии 2', async () => {
    env.site.replace('tf', modifiedTf());
    const result = await env.service.run({ force: true });
    expect(result.outcome).toBe('PUBLISHED');
    expect(result.datasetVersion).toBe(2);
    expect(env.site.requests.filter((r) => r.result === '200')).toHaveLength(1);
    expect(env.site.requests.filter((r) => r.result === '304')).toHaveLength(7);
    expect(result.sources.find((s) => s.sourceId === 'tf')?.changed).toBe(true);
    expect(result.sources.find((s) => s.sourceId === 'uits')?.changed).toBe(false);
    expect((await env.datasets.info())?.datasetVersion).toBe(2);
  });

  it('файл переопубликован с другим ETag, но тем же содержимым: набор не меняется', async () => {
    env.site.replace('uits', fixtureBytes('uits'));
    const result = await env.service.run({ force: true });
    expect(result.outcome).toBe('UP_TO_DATE');
    expect((await env.datasets.info())?.datasetVersion).toBe(1);
  });

  it('сайт недоступен: показывается сохранённое расписание, причина сообщается', async () => {
    env.site.allDown = true;
    const result = await env.service.run({ force: true });
    expect(result.outcome).toBe('UP_TO_DATE');
    expect(result.usedOfflineCopies).toBe(true);
    expect(result.message).toContain('Сайт недоступен');
    expect((await env.datasets.info())?.datasetVersion).toBe(1);
    expect(await env.datasets.groups('uits', 1)).not.toHaveLength(0);
  });

  it('сайт вернул мусор вместо обновлённого файла: прежний файл и набор остаются', async () => {
    env.site.broken.set(SOURCES.find((s) => s.id === 'tf')!.url, 'HTML');
    const result = await env.service.run({ force: true });
    expect(result.usedOfflineCopies).toBe(true);
    expect(result.sources.find((s) => s.sourceId === 'tf')).toMatchObject({ status: 'OFFLINE_CACHED' });
    expect((await env.datasets.info())?.datasetVersion).toBe(1);
  });

  it('новый файл не разобрался: набор версии 1 остаётся, ошибка записана в диагностику, плохая копия не сохранена', async () => {
    // настоящий zip, но не расписание: формат подходит, содержимое нет
    const wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(wb, XLSX.utils.aoa_to_sheet([['совсем', 'другое']]), 'Лист1');
    env.site.replace('tf', new Uint8Array(XLSX.write(wb, { type: 'array', bookType: 'xlsx' })));
    const before = env.files.files.get('tf');

    const result = await env.service.run({ force: true });
    expect(result.outcome).toBe('REJECTED');
    expect(result.datasetVersion).toBe(1);
    expect(result.sources.find((s) => s.sourceId === 'tf')?.status).toBe('FAILED');
    expect((await env.datasets.info())?.datasetVersion).toBe(1);
    expect(env.files.files.get('tf')).toBe(before);

    const latest = (await env.app.recentSyncs())[0];
    const diagnostics = await env.app.diagnosticsOf(latest.id);
    expect(diagnostics.some((d) => d.sourceId === 'tf' && d.severity === 'ERROR')).toBe(true);
  });

  it('падение числа занятий у источника отклоняется как подозрительное', async () => {
    // в файле остаются только заголовки: парсер может не упасть, но занятий станет слишком мало
    const wb = XLSX.read(fixtureBytes('tf'), { type: 'array', cellStyles: true, cellText: true });
    for (const name of wb.SheetNames) {
      for (const [ref, cell] of Object.entries(wb.Sheets[name])) {
        if (!ref.startsWith('!') && (cell as XLSX.CellObject).t === 's' && String((cell as XLSX.CellObject).v).includes('\n')) delete wb.Sheets[name][ref];
      }
    }
    env.site.replace('tf', new Uint8Array(XLSX.write(wb, { type: 'array', bookType: 'xlsx' })));
    const result = await env.service.run({ force: true });
    expect(result.outcome).toBe('REJECTED');
    expect(result.datasetVersion).toBe(1);
    expect((await env.datasets.info())?.datasetVersion).toBe(1);
  });

  it('две одновременные синхронизации выполняются как одна', async () => {
    const [a, b] = await Promise.all([env.service.run({ force: true }), env.service.run({ force: true })]);
    expect(a).toBe(b);
    expect(env.site.requests).toHaveLength(8);
  });
});

describe('подмена набора', () => {
  it('сбой при сборке новой базы оставляет прежний набор рабочим, после устранения сбоя набор обновляется', async () => {
    env = await createEnv();
    await env.service.run({ force: true });
    const slotBefore = await env.app.getSetting('activeDatasetSlot');
    const otherSlot = slotBefore === 'dataset-a.db' ? 'dataset-b.db' : 'dataset-a.db';

    env.site.replace('tf', modifiedTf());
    env.opener.failOnOpen.add(otherSlot);
    const failed = await env.service.run({ force: true });
    expect(failed.outcome).toBe('FAILED');
    expect(await env.app.getSetting('activeDatasetSlot')).toBe(slotBefore);
    expect((await env.datasets.info())?.datasetVersion).toBe(1);
    expect(await env.datasets.groups('uits', 1)).not.toHaveLength(0);

    env.opener.failOnOpen.clear();
    const ok = await env.service.run({ force: true });
    expect(ok.outcome).toBe('PUBLISHED');
    expect(ok.datasetVersion).toBe(2);
    expect(await env.app.getSetting('activeDatasetSlot')).toBe(otherSlot);
  });

  it('смена версии парсера при тех же файлах: файлы разбираются заново', async () => {
    env = await createEnv();
    await env.service.run({ force: true });
    env.site.requests.length = 0;

    const base = createParserRegistry();
    const bumped = new ParserRegistry(
      SOURCES.map((s) => s.parserId)
        .filter((id, i, all) => all.indexOf(id) === i)
        .map((id): ScheduleParser => {
          const parser = base.get(id);
          return { parserId: parser.parserId, parserVersion: `${parser.parserVersion}.1`, parse: (input) => parser.parse(input) };
        }),
    );
    const upgraded = createEnvSharing(env, bumped);
    const result = await upgraded.run({ force: true });
    expect(result.outcome).not.toBe('UP_TO_DATE');
    expect(['UNCHANGED', 'PUBLISHED']).toContain(result.outcome);
    expect(env.site.requests.every((r) => r.result === '304')).toBe(true);
  });
});

/** Новая служба синхронизации поверх тех же баз и файлов, но с другим набором парсеров (как после обновления приложения). */
function createEnvSharing(existing: Env, parsers: ParserRegistry): SyncService {
  return new SyncService({
    config: { ...syncConfig, attempts: 1, retryBaseDelayMs: 1 },
    app: existing.app,
    datasets: existing.datasets,
    files: existing.files,
    fetcher: existing.site.fetcher,
    parsers,
    sha256Bytes,
    sha256Text,
    now: () => existing.clock.now,
    sleep: async () => undefined,
  });
}

