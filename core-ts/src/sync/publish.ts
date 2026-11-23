import { buildDataset, type BuildInput } from '../dataset/builder';
import type { BuildProblem, Dataset } from '../dataset/types';

/**
 * Хранилище опубликованного набора. Приложение реализует его поверх SQLite: `replace` обязано быть атомарным (новая база собирается
 * отдельно и подменяется одним действием), чтобы пользователь никогда не видел частично обновлённую базу.
 */
export interface DatasetStore {
  /** Текущий рабочий набор или null до первой успешной синхронизации. */
  current(): Dataset | null;
  /** Заменяет рабочий набор целиком. Бросает ошибку, не оставляя частичного состояния. */
  replace(dataset: Dataset): void;
}

export type PublishOutcome =
  | { status: 'PUBLISHED'; dataset: Dataset }
  | { status: 'UNCHANGED'; datasetVersion: number; warnings: BuildProblem[] }
  /** Прежний набор остался рабочим. */
  | { status: 'REJECTED'; reasons: BuildProblem[]; keptDatasetVersion: number | null };

/**
 * Собирает набор и публикует его, только если он полный и корректный. Любой отказ оставляет прежний набор нетронутым: импорт и
 * публикация это разные операции, успешный разбор одного файла ещё не означает публикацию набора.
 */
export function publishDataset(input: Omit<BuildInput, 'previous'>, store: DatasetStore): PublishOutcome {
  const current = store.current();
  const result = buildDataset({
    ...input,
    previous:
      current === null
        ? null
        : {
            datasetVersion: current.manifest.datasetVersion,
            contentHash: current.manifest.contentHash,
            entriesBySource: Object.fromEntries(current.manifest.sources.map((s) => [s.sourceId, s.entries])),
          },
  });
  if (result.status === 'REJECTED') return { status: 'REJECTED', reasons: result.reasons, keptDatasetVersion: current?.manifest.datasetVersion ?? null };
  if (result.status === 'UNCHANGED') return { status: 'UNCHANGED', datasetVersion: result.datasetVersion, warnings: result.warnings };
  store.replace(result.dataset);
  return { status: 'PUBLISHED', dataset: result.dataset };
}
