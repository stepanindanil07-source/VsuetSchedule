import { createEnv, type Env } from './support/env';

/** Запросы приложения к настоящей базе набора (SQLite), собранной из восьми реальных файлов. */

let env: Env;
beforeAll(async () => {
  env = await createEnv();
  await env.service.run({ force: true });
});
afterAll(async () => {
  await env.dispose();
});

describe('база набора', () => {
  it('факультеты: показываются те, у которых есть группы', async () => {
    const units = await env.datasets.units();
    expect(units.map((u) => u.id).sort()).toEqual(['eht', 'eiu', 'fspo', 'itf', 'tf', 'uits', 'zao']);
  });

  it('курсы и группы факультета; группа с неопределённым курсом не теряется', async () => {
    const courses = await env.datasets.courses('uits');
    expect(courses).toContain(1);
    expect(courses).toContain(5);
    let total = 0;
    for (const unit of await env.datasets.units()) {
      for (const course of await env.datasets.courses(unit.id)) total += (await env.datasets.groups(unit.id, course)).length;
    }
    expect(total).toBe(300);
  });

  it('занятия группы и её подгруппы читаются из базы', async () => {
    const [group] = await env.datasets.groups('uits', 2);
    const entries = await env.datasets.entries(group.id);
    expect(entries.length).toBeGreaterThan(5);
    expect(entries.every((e) => e.groupId === group.id)).toBe(true);
    expect(entries.every((e) => e.dayOfWeek >= 1 && e.dayOfWeek <= 7)).toBe(true);
    expect(await env.datasets.group(group.id)).toMatchObject({ id: group.id, unitId: 'uits' });
    expect(await env.datasets.group('нет/такой')).toBeNull();
  });

  it('календарь и период набора', async () => {
    const weeks = await env.datasets.calendarWeeks();
    expect(weeks[0]).toMatchObject({ index: 1, start: '2026-08-31', weekType: 'NUMERATOR' });
    expect(weeks[1].weekType).toBe('DENOMINATOR');
    expect(await env.datasets.period()).toEqual({ startDate: '2026-09-01', endDate: '2026-12-31' });
  });

  it('выбор занятий группы использует индекс, а не полный перебор таблицы', async () => {
    const db = (await env.datasets.db())!;
    const plan = await db.all<{ detail: string }>("EXPLAIN QUERY PLAN SELECT * FROM schedule_entries WHERE group_id = 'uits/У-251' ORDER BY day_of_week, start_time");
    expect(plan.map((p) => p.detail).join(' ')).toContain('idx_entries_group_day');
  });

  it('внешние ключи включены: занятие без группы не вставить', async () => {
    const db = (await env.datasets.db())!;
    await expect(
      db.run(
        `INSERT INTO schedule_entries (id, organization_unit_id, group_id, academic_period_id, subject, lesson_type, day_of_week, start_time, end_time, week_type, source_id)
         VALUES ('x', 'uits', 'нет/группы', '2026-autumn', 'x', 'OTHER', 1, '08:00', '09:35', 'EVERY_WEEK', 'uits')`,
      ),
    ).rejects.toThrow();
  });
});

describe('постоянная база приложения', () => {
  it('настройки переживают подмену набора и повторное открытие', async () => {
    await env.app.setSetting('groupId', 'uits/У-251');
    await env.service.run({ force: true });
    expect(await env.app.getSetting('groupId')).toBe('uits/У-251');
    await env.app.setSetting('groupId', null);
    expect(await env.app.getSetting('groupId')).toBeNull();
  });

  it('прерванная синхронизация помечается неудачной при следующем запуске', async () => {
    const id = await env.app.beginSync('2026-11-23T09:00:00Z');
    await env.app.closeAbandonedSyncs('2026-11-23T10:00:00Z');
    const rec = (await env.app.recentSyncs()).find((s) => s.id === id)!;
    expect(rec.outcome).toBe('FAILED');
  });

  it('журнал хранит не более десяти последних синхронизаций', async () => {
    for (let i = 0; i < 12; i++) {
      const id = await env.app.beginSync('2026-11-23T09:00:00Z');
      await env.app.finishSync(id, '2026-11-23T09:00:01Z', 'UP_TO_DATE', 1, null);
    }
    expect((await env.app.recentSyncs(50)).length).toBeLessThanOrEqual(10);
  });
});
