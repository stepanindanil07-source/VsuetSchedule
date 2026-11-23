import { createContext, useContext, type ReactNode } from 'react';
import { useStore } from 'zustand';
import type { AppState, AppStore, Services } from '../../state/store';

interface Value {
  store: AppStore;
  services: Services;
}

const Context = createContext<Value | null>(null);

export function StoreProvider({ store, services, children }: { store: AppStore; services: Services; children: ReactNode }) {
  return <Context.Provider value={{ store, services }}>{children}</Context.Provider>;
}

function useValue(): Value {
  const value = useContext(Context);
  if (value === null) throw new Error('StoreProvider is missing');
  return value;
}

export function useAppStore<T>(selector: (state: AppState) => T): T {
  return useStore(useValue().store, selector);
}

/** Само хранилище: для действий и для чтения состояния вне отрисовки. */
export const useStoreApi = (): AppStore => useValue().store;

/** Службы приложения (базы данных) для экранов, которые читают данные напрямую. */
export const useServices = (): Services => useValue().services;
