import { createContext, useContext } from 'react';

/** Открывает план корпуса на нужной аудитории. null: плана нет (в тестах и до подключения), кнопки «На плане» не показываются. */
export interface PlanOpener {
  openPlan(roomRef: string): void;
}

export const PlanContext = createContext<PlanOpener | null>(null);

export const usePlanOpener = (): PlanOpener | null => useContext(PlanContext);
