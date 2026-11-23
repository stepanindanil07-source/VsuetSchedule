import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { StoreProvider } from '../src/features/common/StoreContext';
import { GroupPicker } from '../src/features/onboarding/GroupPicker';
import { OnboardingScreen } from '../src/features/onboarding/OnboardingScreen';
import { ScheduleScreen } from '../src/features/schedule/ScheduleScreen';
import { SettingsScreen } from '../src/features/settings/SettingsScreen';
import { TodayScreen } from '../src/features/today/TodayScreen';
import { lessonsOn } from '../src/domain/schedule';
import { createAppStore, type AppStore, type Services } from '../src/state/store';
import { createEnv, type Env } from './support/env';

/** Экраны на настоящем наборе (8 реальных файлов) и настоящей SQLite; время зафиксировано: понедельник 23.11.2026, 10:00. */

const NOW = new Date(2026, 10, 23, 10, 0, 0);

let env: Env;
let store: AppStore;
let services: Services;

function ui(children: React.ReactNode) {
  return <StoreProvider store={store} services={services}>{children}</StoreProvider>;
}

async function freshStore(options: { withDataset: boolean }) {
  env = await createEnv();
  if (options.withDataset) await env.service.run({ force: true });
  services = { app: env.app, datasets: env.datasets, sync: env.service, now: () => NOW };
  store = createAppStore(services);
}

beforeEach(() => {
  jest.useFakeTimers({ now: NOW, doNotFake: ['nextTick', 'setImmediate', 'queueMicrotask'] });
});
afterEach(async () => {
  jest.useRealTimers();
  await env?.dispose();
});

describe('первый запуск', () => {
  it('без набора показывает приглашение загрузить; загрузка приводит к выбору группы', async () => {
    await freshStore({ withDataset: false });
    await store.getState().boot();
    expect(store.getState().phase).toBe('needs-dataset');

    await render(ui(<OnboardingScreen onShowDiagnostics={() => undefined} />));
    expect(screen.getByText('Загрузить расписание')).toBeTruthy();
    await fireEvent.press(screen.getByTestId('load'));
    await waitFor(() => expect(store.getState().phase).toBe('needs-group'), { timeout: 30000 });
    expect(store.getState().dataset?.datasetVersion).toBe(1);
  });

  it('ошибка загрузки показывает причину и кнопку «Повторить»', async () => {
    await freshStore({ withDataset: false });
    env.site.allDown = true;
    await store.getState().boot();
    await render(ui(<OnboardingScreen onShowDiagnostics={() => undefined} />));
    await fireEvent.press(screen.getByTestId('load'));
    await waitFor(() => expect(screen.getByText('Повторить')).toBeTruthy(), { timeout: 30000 });
    expect(store.getState().phase).toBe('needs-dataset');
    expect(screen.getAllByText(/Нет соединения/).length).toBeGreaterThan(0);
  });
});

describe('выбор группы', () => {
  it('факультет, курс, группа: выбор сохраняется по естественному ключу', async () => {
    await freshStore({ withDataset: true });
    await store.getState().boot();
    expect(store.getState().phase).toBe('needs-group');

    const done = jest.fn();
    await render(ui(<GroupPicker onDone={done} />));
    await fireEvent.press(await screen.findByText('Факультет управления и информатики в технологических системах'));
    await fireEvent.press(await screen.findByText('2 курс'));
    const [first] = await env.datasets.groups('uits', 2);
    await fireEvent.press(await screen.findByText(first.name));
    await fireEvent.press(screen.getByTestId('save-group'));
    await waitFor(() => expect(done).toHaveBeenCalled());
    expect(store.getState().groupId).toBe(first.id);
    expect(await env.app.getSetting('groupId')).toBe(first.id);
    expect(store.getState().phase).toBe('ready');
  });
});

describe('основное приложение', () => {
  async function ready(groupCourse = 2) {
    await freshStore({ withDataset: true });
    await store.getState().boot();
    const [group] = await env.datasets.groups('uits', groupCourse);
    await store.getState().chooseGroup(group.id, null);
    return group;
  }

  it('«Сегодня»: занятия понедельника по календарю (23.11 это числитель)', async () => {
    const group = await ready();
    const expected = lessonsOn(await env.datasets.entries(group.id), '2026-11-23', 'NUMERATOR', null);
    expect(expected.length).toBeGreaterThan(0);

    await render(ui(<TodayScreen />));
    expect(screen.getByText(/понедельник, 23 ноября/)).toBeTruthy();
    expect(screen.getByText('Неделя: числитель')).toBeTruthy();
    for (const lesson of expected) expect(screen.getAllByText(lesson.entry.subject).length).toBeGreaterThan(0);
  });

  it('«Сегодня»: в 10:00 идущее занятие помечено «Сейчас идёт»', async () => {
    await ready();
    await render(ui(<TodayScreen />));
    const lessons = lessonsOn(store.getState().entries, '2026-11-23', 'NUMERATOR', null);
    const startsBefore10 = lessons.some((l) => l.entry.startTime <= '10:00' && l.entry.endTime > '10:00');
    expect(screen.queryAllByText('Сейчас идёт').length > 0).toBe(startsBefore10);
  });

  it('«Расписание»: неделя с типом недели, переключение на следующую неделю меняет тип', async () => {
    await ready();
    await render(ui(<ScheduleScreen />));
    expect(screen.getByText(/23 ноября – 29 ноября/)).toBeTruthy();
    expect(screen.getByText(/числитель · текущая неделя/)).toBeTruthy();
    await fireEvent.press(screen.getByLabelText('Следующая неделя'));
    expect(screen.getByText(/30 ноября – 6 декабря/)).toBeTruthy();
    expect(screen.getByText(/^знаменатель · к текущей/)).toBeTruthy();
  });

  it('«Настройки»: ручной выбор «Сейчас знаменатель» меняет тип недели и сохраняется', async () => {
    await ready();
    await render(ui(<SettingsScreen onChangeGroup={() => undefined} onDiagnostics={() => undefined} />));
    expect(screen.getByText(/Сейчас: числитель \(по календарю семестра\)/)).toBeTruthy();
    await fireEvent.press(screen.getAllByText('Сейчас знаменатель')[0]);
    await waitFor(() => expect(screen.getByText(/Сейчас: знаменатель \(указано вручную\)/)).toBeTruthy());
    expect(await env.app.getSetting('weekOverride')).toContain('DENOMINATOR');

    await fireEvent.press(screen.getByText('Автоматически'));
    await waitFor(() => expect(screen.getByText(/по календарю семестра/)).toBeTruthy());
    expect(await env.app.getSetting('weekOverride')).toBeNull();
  });

  it('«Настройки»: честно сообщают, что подключены не все файлы университета', async () => {
    await ready();
    await render(ui(<SettingsScreen onChangeGroup={() => undefined} onDiagnostics={() => undefined} />));
    expect(screen.getByText(/не подключено 7/)).toBeTruthy();
  });

  it('выбранная группа пропала из нового набора: приложение просит выбрать заново, а не падает', async () => {
    await ready();
    await env.app.setSetting('groupId', 'uits/НЕТ-999');
    const second = createAppStore(services);
    await second.getState().boot();
    expect(second.getState().phase).toBe('needs-group');
    expect(second.getState().groupMissing).toBe(true);
  });
});
