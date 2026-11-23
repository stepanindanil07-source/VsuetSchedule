import { buildCalendar, validatePeriod, weekTypeOn } from '../src/calendar/calendar';
import { AUTUMN_2026 } from '../src/sources/registry';
import type { AcademicPeriod } from '../src/sources/types';

/** Календарь числителя и знаменателя: чередование от введённого якоря, без чётности ISO-недели и без придуманных дат. */
describe('календарь осеннего семестра 2026–2027', () => {
  const calendar = buildCalendar(AUTUMN_2026);

  it('предоставлен, помечен как ручной, с якорем 31.08.2026 = числитель', () => {
    expect(calendar).toMatchObject({ provided: true, source: 'MANUAL', rule: 'ALTERNATING_FROM_ANCHOR', anchor: { weekStart: '2026-08-31', weekType: 'NUMERATOR' } });
  });

  it('недели от якоря до недели, содержащей конец периода (31.12.2026 это четверг)', () => {
    expect(calendar.weeks[0]).toEqual({ index: 1, start: '2026-08-31', end: '2026-09-06', weekType: 'NUMERATOR' });
    expect(calendar.weeks[1]).toEqual({ index: 2, start: '2026-09-07', end: '2026-09-13', weekType: 'DENOMINATOR' });
    expect(calendar.weeks[calendar.weeks.length - 1]).toEqual({ index: 18, start: '2026-12-28', end: '2027-01-03', weekType: 'DENOMINATOR' });
    expect(calendar.weeks).toHaveLength(18);
  });

  it('недели строго чередуются', () => {
    calendar.weeks.forEach((w, i) => expect(w.weekType).toBe(i % 2 === 0 ? 'NUMERATOR' : 'DENOMINATOR'));
  });

  it.each([
    ['2026-08-31', 'NUMERATOR'], // понедельник первой недели
    ['2026-09-01', 'NUMERATOR'], // первый день семестра, вторник
    ['2026-09-06', 'NUMERATOR'], // воскресенье первой недели
    ['2026-09-07', 'DENOMINATOR'], // понедельник второй
    ['2026-09-09', 'DENOMINATOR'],
    ['2026-09-14', 'NUMERATOR'],
    ['2026-11-22', 'DENOMINATOR'], // неделя 12: 16–22 ноября
    ['2026-12-31', 'DENOMINATOR'],
  ])('%s → %s', (date, expected) => {
    expect(weekTypeOn(calendar, date)).toBe(expected);
  });

  it('вне известных недель ответ «неизвестно», а не придуманный тип', () => {
    expect(weekTypeOn(calendar, '2026-08-30')).toBeNull();
    expect(weekTypeOn(calendar, '2027-01-04')).toBeNull();
    expect(weekTypeOn(calendar, '2025-11-22')).toBeNull();
  });

  it('некорректная дата это «неизвестно»', () => {
    expect(weekTypeOn(calendar, '2026-02-30')).toBeNull();
    expect(weekTypeOn(calendar, 'вчера')).toBeNull();
  });
});

describe('период без достоверного календаря', () => {
  const noCalendar: AcademicPeriod = { ...AUTUMN_2026, calendar: null };

  it('календарь явно «не предоставлен», недель нет', () => {
    expect(buildCalendar(noCalendar)).toEqual({ provided: false, source: null, rule: null, anchor: null, weeks: [] });
  });
  it('тип недели любой даты неизвестен: приложение предложит выбрать вручную', () => {
    expect(weekTypeOn(buildCalendar(noCalendar), '2026-09-09')).toBeNull();
  });
});

describe('проверка периода: опечатка в якоре не должна сдвинуть чётность всех занятий', () => {
  it('корректный период', () => expect(validatePeriod(AUTUMN_2026)).toEqual([]));
  it('якорь не понедельник', () => {
    const p = { ...AUTUMN_2026, calendar: { ...AUTUMN_2026.calendar!, firstWeekStart: '2026-09-01' } };
    expect(validatePeriod(p).join()).toMatch(/Monday/);
  });
  it('первая неделя не содержит первый день семестра', () => {
    const p = { ...AUTUMN_2026, calendar: { ...AUTUMN_2026.calendar!, firstWeekStart: '2026-08-24' } };
    expect(validatePeriod(p).join()).toMatch(/must contain the first day/);
  });
  it('начало не раньше конца', () => {
    expect(validatePeriod({ ...AUTUMN_2026, endDate: '2026-09-01' }).join()).toMatch(/before end/);
  });
  it('даты не календарные', () => {
    expect(validatePeriod({ ...AUTUMN_2026, startDate: '2026-13-01' }).join()).toMatch(/calendar dates/);
  });
});
