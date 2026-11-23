import { create } from 'zustand';
import type { CalendarWeek } from '@core';
import { parseWeekOverride, type PairedWeek, type WeekOverride } from '../domain/weeks';
import { mondayOf, todayIso } from '../domain/dates';
import type { AppRepo } from '../storage/appRepo';
import type { DatasetInfo, DatasetManager, EntryRow, GroupRow } from '../storage/datasetRepo';
import type { SyncProgress, SyncResult, SyncService } from '../sync/syncService';

export interface Services {
  app: AppRepo;
  datasets: DatasetManager;
  sync: SyncService;
  now: () => Date;
}

export type Phase = 'booting' | 'needs-dataset' | 'needs-group' | 'ready' | 'fatal';

export interface AppState {
  phase: Phase;
  fatalError: string | null;
  dataset: DatasetInfo | null;
  /** Выбранная группа сохранена, но в новом наборе её нет: нужно выбрать заново. */
  groupMissing: boolean;
  groupId: string | null;
  subgroup: number | null;
  weekOverride: WeekOverride | null;
  group: GroupRow | null;
  entries: EntryRow[];
  calendar: CalendarWeek[];
  syncing: boolean;
  syncProgress: SyncProgress | null;
  lastSync: SyncResult | null;
  /** Увеличивается при каждой смене данных: экраны перерисовываются. */
  revision: number;

  boot(): Promise<void>;
  sync(force: boolean): Promise<SyncResult>;
  chooseGroup(groupId: string, subgroup: number | null): Promise<void>;
  setSubgroup(subgroup: number | null): Promise<void>;
  /** null возвращает автоматический режим (календарь набора). */
  setWeekType(type: PairedWeek | null): Promise<void>;
}

const KEY_GROUP = 'groupId';
const KEY_SUBGROUP = 'subgroup';
const KEY_WEEK = 'weekOverride';

/** Хранилище состояния приложения. Службы передаются снаружи, поэтому в тестах подставляются настоящие базы в памяти и поддельная сеть. */
export function createAppStore(services: Services) {
  const { app, datasets, sync: syncService, now } = services;

  return create<AppState>((set, get) => {
    /** Перечитывает из базы набор, группу и её занятия. */
    const reload = async (): Promise<void> => {
      const dataset = await datasets.info();
      const groupId = get().groupId;
      let group: GroupRow | null = null;
      let entries: EntryRow[] = [];
      let calendar: CalendarWeek[] = [];
      if (dataset !== null) {
        calendar = await datasets.calendarWeeks();
        if (groupId !== null) {
          group = await datasets.group(groupId);
          if (group !== null) entries = await datasets.entries(groupId);
        }
      }
      const phase: Phase = dataset === null ? 'needs-dataset' : group === null ? 'needs-group' : 'ready';
      set((s) => ({ dataset, group, entries, calendar, phase, groupMissing: dataset !== null && groupId !== null && group === null, revision: s.revision + 1 }));
    };

    return {
      phase: 'booting',
      fatalError: null,
      dataset: null,
      groupMissing: false,
      groupId: null,
      subgroup: null,
      weekOverride: null,
      group: null,
      entries: [],
      calendar: [],
      syncing: false,
      syncProgress: null,
      lastSync: null,
      revision: 0,

      async boot() {
        try {
          await app.closeAbandonedSyncs(now().toISOString());
          const settings = await app.allSettings();
          const subgroup = settings[KEY_SUBGROUP] === undefined ? null : Number(settings[KEY_SUBGROUP]);
          set({ groupId: settings[KEY_GROUP] ?? null, subgroup: Number.isInteger(subgroup) ? subgroup : null, weekOverride: parseWeekOverride(settings[KEY_WEEK] ?? null) });
          await reload();
          // расписание показывается сразу из базы; обновление идёт фоном и не мешает
          if (get().phase !== 'needs-dataset') void get().sync(false);
        } catch (error) {
          set({ phase: 'fatal', fatalError: (error as Error).message });
        }
      },

      async sync(force) {
        if (get().syncing) return syncService.run({ force });
        set({ syncing: true, syncProgress: null });
        try {
          const result = await syncService.run({ force, onProgress: (syncProgress) => set({ syncProgress }) });
          set({ lastSync: result });
          if (result.outcome === 'PUBLISHED') await reload();
          else if (get().phase === 'booting') await reload();
          return result;
        } finally {
          set({ syncing: false, syncProgress: null });
        }
      },

      async chooseGroup(groupId, subgroup) {
        await app.setSetting(KEY_GROUP, groupId);
        await app.setSetting(KEY_SUBGROUP, subgroup === null ? null : String(subgroup));
        set({ groupId, subgroup });
        await reload();
      },

      async setSubgroup(subgroup) {
        await app.setSetting(KEY_SUBGROUP, subgroup === null ? null : String(subgroup));
        set((s) => ({ subgroup, revision: s.revision + 1 }));
      },

      async setWeekType(type) {
        const override: WeekOverride | null = type === null ? null : { anchorWeekStart: mondayOf(todayIso(now())), type };
        await app.setSetting(KEY_WEEK, override === null ? null : JSON.stringify(override));
        set((s) => ({ weekOverride: override, revision: s.revision + 1 }));
      },
    };
  });
}

export type AppStore = ReturnType<typeof createAppStore>;
