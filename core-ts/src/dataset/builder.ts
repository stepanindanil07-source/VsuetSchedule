import { buildCalendar, validatePeriod } from '../calendar/calendar';
import type { ParsedEntry, ParsedGroup } from '../model/types';
import type { AcademicPeriod, KnownGap, OrganizationUnit, ScheduleSource } from '../sources/types';
import {
  SCHEMA_VERSION,
  type BuildProblem,
  type BuildResult,
  type Dataset,
  type DatasetContent,
  type DatasetEntry,
  type DatasetGroup,
  type ManifestSource,
  type PreviousDataset,
  type Sha256,
  type SourceImport,
} from './types';

/**
 * DatasetBuilder: объединяет результаты разбора всех источников в ОДИН полный набор данных университета или отказывается.
 *
 * Правило ТЗ: набор нельзя составлять только из успешных источников, если обязательный источник не обработан. Тогда набор НЕ
 * строится (результат REJECTED с причинами), а приложение продолжает пользоваться прежней рабочей базой. Частичный набор не
 * выдаётся за полный. Чистая функция: ни сети, ни базы данных; запись в SQLite и атомарная замена базы делаются приложением.
 *
 * Шаги: проверка периода и календаря, наличие и успешность обязательных источников, соответствие каждого источника целевому периоду,
 * объединение групп (дубликаты это отказ), проверка занятий, защита от резкого падения числа занятий, детерминированная сортировка,
 * контрольная сумма содержимого, версия, manifest с полнотой охвата.
 */

export interface BuildInput {
  /** Реестр источников: каждый включённый обязательный источник обязан иметь запись в `imports`. */
  sources: readonly ScheduleSource[];
  imports: readonly SourceImport[];
  units: readonly OrganizationUnit[];
  /** Целевой период набора (актуальный семестр). */
  period: AcademicPeriod;
  /** Все известные периоды: по ним определяется период, названный в самом файле. */
  periods: readonly AcademicPeriod[];
  /** Опубликованные университетом, но не подключённые файлы: честно попадают в manifest. */
  knownGaps: readonly KnownGap[];
  previous: PreviousDataset | null;
  /** Момент сборки («YYYY-MM-DDTHH:mm:ssZ»), только для manifest, не влияет на контрольную сумму. */
  now: string;
  sha256: Sha256;
  /** Ниже этой доли занятий прошлого набора по одному источнику файл считается подозрительным. По умолчанию 0,5. */
  minEntriesRatio?: number;
}

const WEEK_ORDER: Record<string, number> = { NUMERATOR: 0, DENOMINATOR: 1, EVERY_WEEK: 2 };
const TIME = /^([01]\d|2[0-3]):[0-5]\d$/;
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;
const MAX_ENTRY_PROBLEMS = 20;

export function buildDataset(input: BuildInput): BuildResult {
  const problems: BuildProblem[] = [];
  const warnings: BuildProblem[] = [];
  const ratio = input.minEntriesRatio ?? 0.5;

  for (const message of validatePeriod(input.period)) problems.push({ code: 'INVALID_PERIOD', message });
  const unitIds = new Set(input.units.map((u) => u.id));
  const importById = new Map(input.imports.map((i) => [i.source.id, i]));

  // 1. обязательные источники: есть ли они вообще и успешны ли
  const included: Array<{ source: ScheduleSource; parsed: ParsedScheduleOk }> = [];
  const requiredEnabled = input.sources.filter((s) => s.enabled && s.required);
  for (const source of input.sources.filter((s) => s.enabled)) {
    const imported = importById.get(source.id);
    if (imported === undefined) {
      if (source.required) problems.push({ code: 'REQUIRED_SOURCE_MISSING', message: `Required source ${source.id} (${source.url}) was not processed`, sourceId: source.id });
      continue;
    }
    if (imported.outcome.status === 'FAILED') {
      const problem = { code: source.required ? 'REQUIRED_SOURCE_FAILED' : 'SOURCE_SKIPPED', message: `Source ${source.id} (${source.url}): ${imported.outcome.error}`, sourceId: source.id };
      (source.required ? problems : warnings).push(problem);
      continue;
    }
    included.push({ source, parsed: imported.outcome });
  }

  // 2. период каждого источника должен совпадать с целевым; единицы должны существовать
  const usable: typeof included = [];
  for (const item of included) {
    const { source, parsed } = item;
    if (!unitIds.has(source.organizationUnitId)) {
      problems.push({ code: 'UNKNOWN_UNIT', message: `Source ${source.id} refers to the unknown unit '${source.organizationUnitId}'`, sourceId: source.id });
      continue;
    }
    const filePeriod = parsed.parsed.period;
    const fileMatch = filePeriod === null ? null : input.periods.find((p) => p.yearFrom === filePeriod.yearFrom && p.term === filePeriod.term) ?? null;
    let effective: string | null;
    if (source.periodId !== null) {
      effective = source.periodId;
      if (filePeriod !== null && (fileMatch === null || fileMatch.id !== source.periodId)) {
        warnings.push({
          code: 'PERIOD_PINNED_MISMATCH',
          message: `Source ${source.id}: the file says ${filePeriod.term} ${filePeriod.yearFrom}-${filePeriod.yearTo} but the source is pinned to period ${source.periodId}; the pinned period is used`,
          sourceId: source.id,
        });
      }
    } else if (filePeriod === null) {
      problems.push({ code: 'NO_PERIOD', message: `Source ${source.id}: the file states no semester and the source is not pinned to a period`, sourceId: source.id });
      continue;
    } else if (fileMatch === null) {
      problems.push({ code: 'PERIOD_NOT_REGISTERED', message: `Source ${source.id}: the semester in the file (${filePeriod.term} ${filePeriod.yearFrom}-${filePeriod.yearTo}) is not registered`, sourceId: source.id });
      continue;
    } else {
      effective = fileMatch.id;
    }
    if (effective !== input.period.id) {
      problems.push({ code: 'PERIOD_MISMATCH', message: `Source ${source.id} belongs to period ${effective}, the dataset is for ${input.period.id}`, sourceId: source.id });
      continue;
    }
    usable.push(item);
  }

  // 3. объединение групп и занятий
  const groups: DatasetGroup[] = [];
  const groupOwner = new Map<string, string>();
  const entries: DatasetEntry[] = [];
  const manifestSources: ManifestSource[] = [];
  let entryProblems = 0;
  for (const { source, parsed } of usable) {
    const schedule = parsed.parsed;
    const sourceEntries: ParsedEntry[] = [];
    const knownCodes = new Set<string>();
    for (const g of schedule.groups) {
      const id = `${source.organizationUnitId}/${g.code}`;
      const owner = groupOwner.get(id);
      if (owner !== undefined) {
        problems.push({ code: 'DUPLICATE_GROUP', message: `Group '${g.code}' of unit '${source.organizationUnitId}' appears in sources ${owner} and ${source.id}`, sourceId: source.id });
        continue;
      }
      groupOwner.set(id, source.id);
      knownCodes.add(g.code);
      groups.push(toGroup(id, source, g, schedule.entries));
    }
    for (const e of schedule.entries) {
      if (!knownCodes.has(e.groupCode)) {
        if (entryProblems++ < MAX_ENTRY_PROBLEMS) problems.push({ code: 'ENTRY_UNKNOWN_GROUP', message: `A lesson refers to the unknown group '${e.groupCode}' (${e.sheet}!${e.range})`, sourceId: source.id });
        continue;
      }
      const invalid = entryProblem(e);
      if (invalid !== null) {
        if (entryProblems++ < MAX_ENTRY_PROBLEMS) problems.push({ code: 'INVALID_ENTRY', message: `${invalid} (${e.sheet}!${e.range})`, sourceId: source.id });
        continue;
      }
      sourceEntries.push(e);
      entries.push(toEntry(source, e, input.period.id));
    }
    const previousCount = input.previous?.entriesBySource?.[source.id];
    if (previousCount !== undefined && previousCount > 0 && sourceEntries.length < previousCount * ratio) {
      problems.push({
        code: 'SUSPICIOUS_DROP',
        message: `Source ${source.id}: ${sourceEntries.length} lessons instead of ${previousCount} in the previous dataset (less than ${Math.round(ratio * 100)}%)`,
        sourceId: source.id,
      });
    }
    manifestSources.push({
      sourceId: source.id,
      organizationUnitId: source.organizationUnitId,
      url: source.url,
      parserId: schedule.parserKey,
      parserVersion: schedule.diagnostics.parserVersion,
      fileSha256: parsed.fileSha256,
      fetchedAt: parsed.fetchedAt,
      groups: schedule.groups.filter((g) => knownCodes.has(g.code)).length,
      entries: sourceEntries.length,
    });
  }
  if (entryProblems > MAX_ENTRY_PROBLEMS) problems.push({ code: 'INVALID_ENTRY', message: `… and ${entryProblems - MAX_ENTRY_PROBLEMS} more invalid lessons` });
  if (groups.length === 0 || entries.length === 0) problems.push({ code: 'EMPTY_DATASET', message: 'The dataset would contain no groups or no lessons' });

  if (problems.length > 0) return { status: 'REJECTED', reasons: problems };

  // 4. детерминированный порядок: одни и те же данные дают одну и ту же контрольную сумму
  groups.sort((a, b) => cmp(a.id, b.id));
  entries.sort(compareEntries);
  const counters = new Map<string, number>();
  for (const e of entries) {
    const n = (counters.get(e.groupId) ?? 0) + 1;
    counters.set(e.groupId, n);
    e.id = `${e.groupId}#${n}`;
  }
  for (const g of groups) {
    if (!counters.has(g.id)) warnings.push({ code: 'EMPTY_GROUP', message: `Group '${g.name}' of unit '${g.organizationUnitId}' has no lessons` });
  }

  const usedUnits = new Set(groups.map((g) => g.organizationUnitId));
  const period = { id: input.period.id, academicYear: input.period.academicYear, term: input.period.term, startDate: input.period.startDate, endDate: input.period.endDate };
  const content: DatasetContent = {
    organizationUnits: input.units.filter((u) => usedUnits.has(u.id)).map((u) => ({ id: u.id, name: u.name, type: u.type })).sort((a, b) => cmp(a.id, b.id)),
    academicPeriods: [period],
    groups,
    scheduleEntries: entries,
    academicCalendar: buildCalendar(input.period),
  };
  const serialized = JSON.stringify(content);
  const contentHash = input.sha256(JSON.stringify({ schemaVersion: SCHEMA_VERSION, content: serialized }));

  if (input.previous !== null && input.previous.contentHash === contentHash) {
    return { status: 'UNCHANGED', datasetVersion: input.previous.datasetVersion, contentHash, warnings };
  }

  const datasetVersion = input.previous === null ? 1 : input.previous.datasetVersion + 1;
  manifestSources.sort((a, b) => cmp(a.sourceId, b.sourceId));
  // Полнота: нет известных пробелов И включён каждый включённый источник (пропущенный необязательный тоже делает набор неполным).
  const enabledCount = input.sources.filter((s) => s.enabled).length;
  const complete = input.knownGaps.length === 0 && manifestSources.length === enabledCount;
  const dataset: Dataset = {
    content,
    manifest: {
      datasetId: `vsuet-${input.period.id}-v${datasetVersion}-${contentHash.slice(0, 12)}`,
      datasetVersion,
      schemaVersion: SCHEMA_VERSION,
      academicPeriod: period,
      generatedAt: input.now,
      contentHash,
      sizeBytes: utf8Length(serialized),
      counts: { organizationUnits: content.organizationUnits.length, groups: groups.length, scheduleEntries: entries.length },
      sources: manifestSources,
      coverage: { complete, requiredSources: requiredEnabled.length, includedSources: manifestSources.length, knownGaps: [...input.knownGaps] },
      calendar: { provided: content.academicCalendar.provided, source: content.academicCalendar.source },
      warnings,
    },
  };
  return { status: 'BUILT', dataset };
}

type ParsedScheduleOk = Extract<SourceImport['outcome'], { status: 'OK' }>;

function toGroup(id: string, source: ScheduleSource, g: ParsedGroup, entries: readonly ParsedEntry[]): DatasetGroup {
  let maxSubgroup = 0;
  for (const e of entries) {
    if (e.groupCode === g.code && e.subgroup !== null && e.subgroup > maxSubgroup) maxSubgroup = e.subgroup;
  }
  return {
    id,
    organizationUnitId: source.organizationUnitId,
    name: g.code,
    course: g.course ?? source.defaultCourse,
    program: null,
    subgroupCount: maxSubgroup > 0 ? maxSubgroup : null,
  };
}

function toEntry(source: ScheduleSource, e: ParsedEntry, periodId: string): DatasetEntry {
  return {
    id: '',
    organizationUnitId: source.organizationUnitId,
    groupId: `${source.organizationUnitId}/${e.groupCode}`,
    academicPeriodId: periodId,
    subject: e.subject,
    lessonType: e.lessonType,
    rawLessonType: e.rawLessonType,
    teacher: e.teacher,
    classroom: e.room,
    dayOfWeek: e.dayOfWeek,
    specificDate: e.date,
    startTime: e.startTime,
    endTime: e.endTime,
    weekType: e.weekType,
    subgroupNumber: e.subgroup,
    sourceId: source.id,
    sourceReference: `${e.sheet}!${e.range}`,
    originalText: e.rawText,
  };
}

/** Проверка занятия, не доверяющая парсеру: ошибка парсера не должна попасть в набор. null, если всё в порядке. */
function entryProblem(e: ParsedEntry): string | null {
  if (!Number.isInteger(e.dayOfWeek) || e.dayOfWeek < 1 || e.dayOfWeek > 7) return `day of week ${e.dayOfWeek} is not 1-7`;
  if (!TIME.test(e.startTime) || !TIME.test(e.endTime)) return `bad time ${e.startTime}-${e.endTime}`;
  if (e.startTime >= e.endTime) return `start ${e.startTime} is not before end ${e.endTime}`;
  if (!(e.weekType in WEEK_ORDER)) return `unknown week type ${e.weekType}`;
  if (e.subgroup !== null && (!Number.isInteger(e.subgroup) || e.subgroup < 1)) return `subgroup ${e.subgroup} is not a positive integer`;
  if (e.subject.trim() === '') return 'empty subject';
  if (e.date !== null) {
    if (!ISO_DATE.test(e.date)) return `bad date ${e.date}`;
    const wd = new Date(`${e.date}T00:00:00Z`).getUTCDay() || 7;
    if (wd !== e.dayOfWeek) return `date ${e.date} is not day ${e.dayOfWeek} of the week`;
    if (e.weekType !== 'EVERY_WEEK') return 'a lesson on a date must have week type EVERY_WEEK';
  }
  return null;
}

function cmp(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

function compareEntries(a: DatasetEntry, b: DatasetEntry): number {
  return (
    cmp(a.groupId, b.groupId) ||
    cmp(a.specificDate ?? '', b.specificDate ?? '') ||
    a.dayOfWeek - b.dayOfWeek ||
    cmp(a.startTime, b.startTime) ||
    (WEEK_ORDER[a.weekType] ?? 9) - (WEEK_ORDER[b.weekType] ?? 9) ||
    (a.subgroupNumber ?? 0) - (b.subgroupNumber ?? 0) ||
    cmp(a.subject, b.subject) ||
    cmp(a.sourceReference ?? '', b.sourceReference ?? '') ||
    cmp(a.originalText ?? '', b.originalText ?? '')
  );
}

/** Длина строки в байтах UTF-8 (без TextEncoder, которого может не быть в среде выполнения). */
export function utf8Length(s: string): number {
  let bytes = 0;
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    if (c < 0x80) bytes += 1;
    else if (c < 0x800) bytes += 2;
    else if (c >= 0xd800 && c <= 0xdbff) {
      bytes += 4; // суррогатная пара
      i++;
    } else bytes += 3;
  }
  return bytes;
}
