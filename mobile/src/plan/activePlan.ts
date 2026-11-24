import { DEMO_PLAN } from './demoMainFloor1';
import type { PlanData } from './types';

/**
 * Какой план показывает приложение: утверждённый эталон (etalon/mainFloor1.ts, в git не хранится). Если его рядом нет (например, в CI
 * или в чистой копии репозитория), показывается схематичный демо-план из репозитория. Рабочие сборки из tools/plan-extract (generated/)
 * приложение не читает: в эталон они переносятся только после проверки.
 */
function load(): PlanData {
  try {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    return (require('./etalon/mainFloor1') as { ETALON_PLAN: PlanData }).ETALON_PLAN;
  } catch {
    return DEMO_PLAN;
  }
}

export const ACTIVE_PLAN: PlanData = load();
