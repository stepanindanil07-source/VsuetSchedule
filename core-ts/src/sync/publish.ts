import { buildDataset, type BuildInput } from '../dataset/builder';
import type { BuildProblem, Dataset, PreviousDataset } from '../dataset/types';

/**
 * Хранилище опубликованного набора. Приложение реализует его поверх SQLite: `replace` обязано быть атомарным (новая база собирается
 * отдельно и подменяется одним действием), чтобы пользователь никогда не видел частично обновлённую базу.
 */
export interface DatasetStore {
  /**
   * Краткие сведения о текущем рабочем наборе (версия, контрольная сумма, число занятий по источникам) или null до первой
   * успешной синхронизации. Целиком набор для сравнения не нужен: приложению не придётся читать тысячи занятий из базы.
   */
  previous(): PreviousDataset | null | Promise<PreviousDataset | null>;
  /** Заменяет рабочий набор целиком. Бросает ошибку, не оставляя частичного состояния. */
  replace(dataset: Dataset): void | Promise<void>;
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
export async function publishDataset(input: Omit<BuildInput, 'previous'>, store: DatasetStore): Promise<PublishOutcome> {
  const previous = await store.previous();
  const result = buildDataset({ ...input, previous });
  if (result.status === 'REJECTED') return { status: 'REJECTED', reasons: result.reasons, keptDatasetVersion: previous?.datasetVersion ?? null };
  if (result.status === 'UNCHANGED') return { status: 'UNCHANGED', datasetVersion: result.datasetVersion, warnings: result.warnings };
  await store.replace(result.dataset);
  return { status: 'PUBLISHED', dataset: result.dataset };
}
