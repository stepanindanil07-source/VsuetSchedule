import { buildDataset } from '../src/dataset/builder';
import { publishDataset, type DatasetStore } from '../src/sync/publish';
import type { Dataset, SourceImport } from '../src/dataset/types';
import { AUTUMN_2026, SOURCES } from '../src/sources/registry';
import { allImports, baseInput, importSource, sha256 } from './datasetSupport';

/** Сборка полного набора из 8 реальных файлов и все правила отказа. */

function built(input = baseInput()): Dataset {
  const r = buildDataset(input);
  if (r.status !== 'BUILT') throw new Error(`ожидалась сборка, получено ${r.status}: ${JSON.stringify((r as { reasons?: unknown }).reasons)}`);
  return r.dataset;
}

const failed = (id: string): SourceImport => ({ source: SOURCES.find((s) => s.id === id)!, outcome: { status: 'FAILED', error: 'HTTP 503' } });
const replaceImport = (id: string, replacement: SourceImport): SourceImport[] => allImports().map((i) => (i.source.id === id ? replacement : i));

describe('полный набор из восьми реальных файлов', () => {
  const dataset = built();

  it('все подразделения, группы и занятия университета, подключённых к приложению', () => {
    expect(dataset.manifest.counts).toEqual({ organizationUnits: 7, groups: 300, scheduleEntries: 7285 });
    expect(dataset.content.groups).toHaveLength(300);
    expect(dataset.content.scheduleEntries).toHaveLength(7285);
  });

  it('manifest: версии, период, хеш, размер, календарь', () => {
    const m = dataset.manifest;
    expect(m).toMatchObject({ datasetVersion: 1, schemaVersion: 1, generatedAt: '2026-11-23T12:00:00Z', calendar: { provided: true, source: 'MANUAL' } });
    expect(m.academicPeriod).toEqual({ id: '2026-autumn', academicYear: '2026-2027', term: 'AUTUMN', startDate: '2026-09-01', endDate: '2026-12-31' });
    expect(m.contentHash).toMatch(/^[0-9a-f]{64}$/);
    expect(m.datasetId).toBe(`vsuet-2026-autumn-v1-${m.contentHash.slice(0, 12)}`);
    expect(m.sizeBytes).toBe(Buffer.byteLength(JSON.stringify(dataset.content), 'utf8'));
  });

  it('manifest перечисляет источники с SHA-256 файла, версией парсера и числом занятий', () => {
    expect(dataset.manifest.sources).toHaveLength(8);
    const uits = dataset.manifest.sources.find((s) => s.sourceId === 'uits')!;
    expect(uits).toMatchObject({ parserId: 'vsuet-grid-xlsx', parserVersion: '3', groups: 45, entries: 1066, organizationUnitId: 'uits' });
    expect(uits.fileSha256).toMatch(/^[0-9a-f]{64}$/);
    expect(dataset.manifest.sources.reduce((n, s) => n + s.entries, 0)).toBe(7285);
  });

  it('полнота охвата честно «нет»: опубликовано 15 файлов, подключено 8, семь перечислены как пробелы', () => {
    const c = dataset.manifest.coverage;
    expect(c).toMatchObject({ complete: false, requiredSources: 8, includedSources: 8 });
    expect(c.knownGaps).toHaveLength(7);
    expect(c.knownGaps.map((g) => g.url).join()).toContain('ocz/tf_1.xlsx');
  });

  it('полнота «да» только когда пробелов нет', () => {
    expect(built(baseInput({ knownGaps: [] })).manifest.coverage.complete).toBe(true);
  });

  it('группа идентифицируется естественным ключом «подразделение/код», а не номером', () => {
    expect(dataset.content.groups.map((g) => g.id)).toContain('uits/У-251');
    expect(dataset.content.groups.find((g) => g.id === 'fspo/ИСП-255')).toMatchObject({ organizationUnitId: 'fspo', name: 'ИСП-255', course: 2 });
  });

  it('курс известен у каждой группы; у заочки его задаёт источник', () => {
    expect(dataset.content.groups.filter((g) => g.course === null)).toEqual([]);
    expect(dataset.content.groups.filter((g) => g.organizationUnitId === 'zao').every((g) => g.course === 5)).toBe(true);
  });

  it('типы подразделений: факультеты, колледж и прочее', () => {
    expect(Object.fromEntries(dataset.content.organizationUnits.map((u) => [u.id, u.type]))).toEqual({
      eht: 'FACULTY', eiu: 'FACULTY', fspo: 'COLLEGE', itf: 'FACULTY', tf: 'FACULTY', uits: 'FACULTY', zao: 'OTHER',
    });
  });

  it('число подгрупп: наибольший встретившийся номер, null если подгрупп нет; не ограничено двумя', () => {
    const counts = dataset.content.groups.map((g) => g.subgroupCount);
    expect(counts.some((n) => n === 2)).toBe(true);
    expect(counts.some((n) => n === null)).toBe(true);
    expect(Math.max(...counts.map((n) => n ?? 0))).toBeGreaterThanOrEqual(2);
  });

  it('ссылочная целостность: у каждого занятия есть группа, подразделение и период; идентификаторы уникальны', () => {
    const groups = new Set(dataset.content.groups.map((g) => g.id));
    const units = new Set(dataset.content.organizationUnits.map((u) => u.id));
    const ids = new Set<string>();
    for (const e of dataset.content.scheduleEntries) {
      expect(groups.has(e.groupId)).toBe(true);
      expect(units.has(e.organizationUnitId)).toBe(true);
      expect(e.academicPeriodId).toBe('2026-autumn');
      expect(ids.has(e.id)).toBe(false);
      ids.add(e.id);
    }
    expect(ids.size).toBe(7285);
  });

  it('занятие хранит исходный текст, источник и координаты в файле', () => {
    const e = dataset.content.scheduleEntries.find((x) => x.groupId === 'uits/У-251')!;
    expect(e.originalText).toBeTruthy();
    expect(e.sourceId).toBe('uits');
    expect(e.sourceReference).toMatch(/^.+![A-Z]+\d+(:[A-Z]+\d+)?$/);
  });

  it('исходное обозначение типа занятия сохраняется: «пр.», «л.», «лаб.», «экз.», «зач.»', () => {
    const raw = new Set(dataset.content.scheduleEntries.map((e) => e.rawLessonType));
    for (const marker of ['пр.', 'л.', 'лаб.', 'экз.', 'зач.']) expect(raw.has(marker)).toBe(true);
    for (const e of dataset.content.scheduleEntries) {
      if (e.rawLessonType === 'пр.') expect(e.lessonType).toBe('PRACTICE');
      if (e.rawLessonType === 'экз.') expect(e.lessonType).toBe('EXAM');
    }
  });

  it('разовые занятия заочки имеют дату, недельные нет; тип недели у разовых EVERY_WEEK', () => {
    const dated = dataset.content.scheduleEntries.filter((e) => e.specificDate !== null);
    expect(dated).toHaveLength(882);
    expect(dated.every((e) => e.weekType === 'EVERY_WEEK' && e.organizationUnitId === 'zao')).toBe(true);
    expect(dataset.content.scheduleEntries.filter((e) => e.specificDate === null).every((e) => e.organizationUnitId !== 'zao')).toBe(true);
  });

  it('календарь внутри набора', () => {
    expect(dataset.content.academicCalendar.provided).toBe(true);
    expect(dataset.content.academicCalendar.weeks).toHaveLength(18);
  });
});

describe('версия и контрольная сумма содержимого', () => {
  it('одни и те же данные дают одну и ту же сумму, время сборки на неё не влияет', () => {
    const a = built(baseInput({ now: '2026-11-23T12:00:00Z' }));
    const b = built(baseInput({ now: '2027-01-01T00:00:00Z' }));
    expect(b.manifest.contentHash).toBe(a.manifest.contentHash);
    expect(b.manifest.datasetId).toBe(a.manifest.datasetId);
  });

  it('порядок обработки источников на сумму не влияет', () => {
    const a = built();
    const b = built(baseInput({ imports: [...allImports()].reverse() }));
    expect(b.manifest.contentHash).toBe(a.manifest.contentHash);
  });

  it('содержимое не изменилось: новая версия не создаётся', () => {
    const first = built();
    const r = buildDataset(baseInput({ previous: { datasetVersion: 7, contentHash: first.manifest.contentHash } }));
    expect(r).toMatchObject({ status: 'UNCHANGED', datasetVersion: 7, contentHash: first.manifest.contentHash });
  });

  it('содержимое изменилось: версия растёт на единицу, schemaVersion остаётся прежней', () => {
    const r = buildDataset(baseInput({ previous: { datasetVersion: 7, contentHash: 'old' } }));
    expect(r.status).toBe('BUILT');
    if (r.status === 'BUILT') expect(r.dataset.manifest).toMatchObject({ datasetVersion: 8, schemaVersion: 1 });
  });

  it('изменение одного занятия меняет сумму', () => {
    const imports = allImports();
    const uits = imports.find((i) => i.source.id === 'uits')!;
    if (uits.outcome.status !== 'OK') throw new Error('нужен успешный разбор');
    uits.outcome.parsed.entries[0] = { ...uits.outcome.parsed.entries[0], teacher: 'Другой П.П.' };
    expect(built(baseInput({ imports })).manifest.contentHash).not.toBe(built().manifest.contentHash);
  });
});

describe('отказ публикации: неполный или некорректный набор никогда не выдаётся за полный', () => {
  it('один обязательный источник завершился ошибкой: набор не строится', () => {
    const r = buildDataset(baseInput({ imports: replaceImport('itf', failed('itf')) }));
    expect(r.status).toBe('REJECTED');
    if (r.status === 'REJECTED') expect(r.reasons).toEqual([expect.objectContaining({ code: 'REQUIRED_SOURCE_FAILED', sourceId: 'itf' })]);
  });

  it('обязательный источник вообще не обработан', () => {
    const r = buildDataset(baseInput({ imports: allImports().filter((i) => i.source.id !== 'zao-5') }));
    expect(r).toMatchObject({ status: 'REJECTED', reasons: [expect.objectContaining({ code: 'REQUIRED_SOURCE_MISSING', sourceId: 'zao-5' })] });
  });

  it('все причины отказа перечисляются, а не только первая', () => {
    const imports = replaceImport('itf', failed('itf')).map((i) => (i.source.id === 'tf' ? failed('tf') : i));
    const r = buildDataset(baseInput({ imports }));
    expect(r.status === 'REJECTED' && r.reasons.map((x) => x.sourceId).sort()).toEqual(['itf', 'tf']);
  });

  it('необязательный источник с ошибкой пропускается, но набор помечается неполным', () => {
    const sources = SOURCES.map((s) => (s.id === 'zao-5' ? { ...s, required: false } : s));
    const r = buildDataset(baseInput({ sources, imports: replaceImport('zao-5', failed('zao-5')), knownGaps: [] }));
    expect(r.status).toBe('BUILT');
    if (r.status === 'BUILT') {
      expect(r.dataset.manifest.coverage.complete).toBe(false);
      expect(r.dataset.manifest.warnings.map((w) => w.code)).toContain('SOURCE_SKIPPED');
      expect(r.dataset.manifest.counts.scheduleEntries).toBe(7285 - 882);
    }
  });

  it('отключённый источник не обязателен и в набор не входит', () => {
    const sources = SOURCES.map((s) => (s.id === 'zao-5' ? { ...s, enabled: false } : s));
    const r = buildDataset(baseInput({ sources, imports: allImports().filter((i) => i.source.id !== 'zao-5'), knownGaps: [] }));
    expect(r.status).toBe('BUILT');
    if (r.status === 'BUILT') expect(r.dataset.manifest.coverage).toMatchObject({ complete: true, requiredSources: 7, includedSources: 7 });
  });

  it('файл относится к другому семестру: отказ', () => {
    const spring = { ...AUTUMN_2026, id: '2026-spring', term: 'SPRING' as const, startDate: '2027-02-08', endDate: '2027-06-30', calendar: null };
    const sources = SOURCES.map((s) => (s.id === 'fspo-1' ? { ...s, periodId: spring.id } : s));
    const imports = allImports().map((i) => (i.source.id === 'fspo-1' ? { ...i, source: sources.find((s) => s.id === 'fspo-1')! } : i));
    const r = buildDataset(baseInput({ sources, imports, periods: [AUTUMN_2026, spring] }));
    expect(r).toMatchObject({ status: 'REJECTED', reasons: [expect.objectContaining({ code: 'PERIOD_MISMATCH', sourceId: 'fspo-1' })] });
  });

  it('без привязки eiu.xlsx с ошибкой в тексте («2025-2026») не проходит: семестр не зарегистрирован', () => {
    const unpinned = { ...SOURCES.find((s) => s.id === 'eiu')!, periodId: null };
    const sources = SOURCES.map((s) => (s.id === 'eiu' ? unpinned : s));
    const imports = allImports().map((i) => (i.source.id === 'eiu' ? { ...i, source: unpinned } : i));
    const r = buildDataset(baseInput({ sources, imports }));
    expect(r.status === 'REJECTED' && r.reasons[0].code).toBe('PERIOD_NOT_REGISTERED');
  });

  it('привязанный источник с неверным текстом в файле принимается, расхождение остаётся предупреждением', () => {
    const w = built().manifest.warnings.find((x) => x.code === 'PERIOD_PINNED_MISMATCH');
    expect(w).toMatchObject({ sourceId: 'eiu' });
  });

  it('одна и та же группа подразделения в двух файлах: отказ, а не молчаливая перезапись', () => {
    const copy = { ...SOURCES.find((s) => s.id === 'uits')!, id: 'uits-copy', url: 'https://example.test/uits-copy.xlsx' };
    const original = allImports().find((i) => i.source.id === 'uits')!;
    const r = buildDataset(baseInput({ sources: [...SOURCES, copy], imports: [...allImports(), { ...original, source: copy }] }));
    expect(r.status === 'REJECTED' && r.reasons[0]).toMatchObject({ code: 'DUPLICATE_GROUP' });
  });

  it('резкое падение числа занятий источника относительно прошлого набора: отказ', () => {
    const r = buildDataset(baseInput({ previous: { datasetVersion: 3, contentHash: 'old', entriesBySource: { uits: 5000 } } }));
    expect(r.status === 'REJECTED' && r.reasons.map((x) => x.code)).toEqual(['SUSPICIOUS_DROP']);
  });

  it('ошибочное занятие от парсера не попадает в набор', () => {
    const imports = allImports();
    const uits = imports.find((i) => i.source.id === 'uits')!;
    if (uits.outcome.status !== 'OK') throw new Error('нужен успешный разбор');
    uits.outcome.parsed.entries.push({ ...uits.outcome.parsed.entries[0], startTime: '10:00', endTime: '09:00' });
    const r = buildDataset(baseInput({ imports }));
    expect(r.status === 'REJECTED' && r.reasons[0].code).toBe('INVALID_ENTRY');
  });

  it('неверный якорь календаря: набор не публикуется', () => {
    const bad = { ...AUTUMN_2026, calendar: { ...AUTUMN_2026.calendar!, firstWeekStart: '2026-09-01' } };
    const r = buildDataset(baseInput({ period: bad, periods: [bad] }));
    expect(r.status === 'REJECTED' && r.reasons[0].code).toBe('INVALID_PERIOD');
  });

  it('период без календаря допустим: набор строится, отсутствие календаря явно обозначено', () => {
    const noCalendar = { ...AUTUMN_2026, calendar: null };
    const d = built(baseInput({ period: noCalendar, periods: [noCalendar] }));
    expect(d.manifest.calendar).toEqual({ provided: false, source: null });
    expect(d.content.academicCalendar).toMatchObject({ provided: false, weeks: [] });
  });
});

describe('публикация: прежний рабочий набор остаётся при любой ошибке', () => {
  class MemoryStore implements DatasetStore {
    replaced = 0;
    constructor(private dataset: Dataset | null = null) {}
    current(): Dataset | null {
      return this.dataset;
    }
    replace(d: Dataset): void {
      this.dataset = d;
      this.replaced++;
    }
  }
  const { previous: _p, ...noPrevious } = baseInput();

  it('первая публикация сохраняет набор версии 1', () => {
    const store = new MemoryStore();
    const outcome = publishDataset(noPrevious, store);
    expect(outcome.status).toBe('PUBLISHED');
    expect(store.current()?.manifest.datasetVersion).toBe(1);
  });

  it('ошибка источника: набор не заменяется, остаётся версия 1', () => {
    const store = new MemoryStore();
    publishDataset(noPrevious, store);
    const before = store.current();
    const outcome = publishDataset({ ...noPrevious, imports: replaceImport('eht', failed('eht')) }, store);
    expect(outcome).toMatchObject({ status: 'REJECTED', keptDatasetVersion: 1 });
    expect(store.current()).toBe(before);
    expect(store.replaced).toBe(1);
  });

  it('то же содержимое: ничего не заменяется', () => {
    const store = new MemoryStore();
    publishDataset(noPrevious, store);
    const outcome = publishDataset(noPrevious, store);
    expect(outcome).toMatchObject({ status: 'UNCHANGED', datasetVersion: 1 });
    expect(store.replaced).toBe(1);
  });

  it('изменённое содержимое заменяет набор целиком и поднимает версию', () => {
    const store = new MemoryStore();
    publishDataset(noPrevious, store);
    const imports = allImports();
    const tf = imports.find((i) => i.source.id === 'tf')!;
    if (tf.outcome.status !== 'OK') throw new Error('нужен успешный разбор');
    tf.outcome.parsed.entries[0] = { ...tf.outcome.parsed.entries[0], room: '999' };
    const outcome = publishDataset({ ...noPrevious, imports }, store);
    expect(outcome.status).toBe('PUBLISHED');
    expect(store.current()?.manifest.datasetVersion).toBe(2);
  });

  it('падение числа занятий относительно ТЕКУЩЕГО опубликованного набора отклоняется', () => {
    const store = new MemoryStore();
    publishDataset(noPrevious, store);
    const imports = allImports();
    const uits = imports.find((i) => i.source.id === 'uits')!;
    if (uits.outcome.status !== 'OK') throw new Error('нужен успешный разбор');
    uits.outcome.parsed.entries = uits.outcome.parsed.entries.slice(0, 10);
    const outcome = publishDataset({ ...noPrevious, imports }, store);
    expect(outcome).toMatchObject({ status: 'REJECTED', keptDatasetVersion: 1 });
    expect(store.current()?.manifest.datasetVersion).toBe(1);
  });
});

it('SHA-256 из тестовой подпорки совпадает с известным значением', () => {
  expect(sha256('abc')).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
  expect(importSource(SOURCES[0]).outcome.status).toBe('OK');
});
