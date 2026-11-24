import { DEMO_PLAN } from './demoMainFloor1';
import type { PlanData } from './types';

/**
 * Какой план показывает приложение. Если рядом лежит сгенерированный из снимка план (generated/mainFloor1.ts, в git не хранится),
 * берётся он: полный контур и все помещения. Иначе схематичный демо-план из репозитория.
 */
function load(): PlanData {
  try {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    return (require('./generated/mainFloor1') as { GENERATED_PLAN: PlanData }).GENERATED_PLAN;
  } catch {
    return DEMO_PLAN;
  }
}

export const ACTIVE_PLAN: PlanData = load();
