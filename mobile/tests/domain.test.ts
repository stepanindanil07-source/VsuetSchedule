import { addDays, dayMonth, longDate, mondayOf, parseMinutes, todayIso, weekdayOf } from '../src/domain/dates';
import { lessonsOn, nextLessonDay, phasesAt, subgroupNumbers, weekDates } from '../src/domain/schedule';
import { parseWeekOverride, resolveWeekType, type WeekOverride } from '../src/domain/weeks';
import type { EntryRow } from '../src/storage/datasetRepo';

const entry = (over: Partial<EntryRow>): EntryRow => ({
  id: String(Math.random()),
  groupId: 'uits/У-251',
  subject: 'Предмет',
  lessonType: 'LECTURE',
  rawLessonType: 'л.',
  teacher: null,
  classroom: null,
  dayOfWeek: 1,
  specificDate: null,
  startTime: '08:00',
  endTime: '09:35',
  weekType: 'EVERY_WEEK',
  subgroupNumber: null,
  sourceId: 'uits',
  originalText: null,
  ...over,
});

describe('даты', () => {
  it('день недели и понедельник', () => {
    expect(weekdayOf('2026-11-23')).toBe(1); // понедельник
    expect(weekdayOf('2026-11-29')).toBe(7);
    expect(mondayOf('2026-11-27')).toBe('2026-11-23');
    expect(mondayOf('2026-11-23')).toBe('2026-11-23');
    expect(addDays('2026-12-31', 1)).toBe('2027-01-01');
    expect(addDays('2026-03-01', -1)).toBe('2026-02-28');
  });
  it('форматирование и разбор времени', () => {
    expect(longDate('2026-11-23')).toBe('понедельник, 23 ноября');
    expect(dayMonth('2026-09-01')).toBe('1 сентября');
    expect(parseMinutes('08.00')).toBeNaN();
    expect(parseMinutes('8:00')).toBe(480);
    expect(parseMinutes('09:35')).toBe(575);
  });
  it('«сегодня» это местная календарная дата', () => {
    expect(todayIso(new Date(2026, 10, 23, 23, 59))).toBe('2026-11-23');
    expect(todayIso(new Date(2026, 10, 24, 0, 1))).toBe('2026-11-24');
  });
});

describe('тип недели', () => {
  const calendar = [
    { index: 1, start: '2026-08-31', end: '2026-09-06', weekType: 'NUMERATOR' as const },
    { index: 2, start: '2026-09-07', end: '2026-09-13', weekType: 'DENOMINATOR' as const },
  ];

  it('по календарю набора', () => {
    expect(resolveWeekType('2026-09-02', calendar, null)).toEqual({ type: 'NUMERATOR', origin: 'CALENDAR' });
    expect(resolveWeekType('2026-09-13', calendar, null)).toEqual({ type: 'DENOMINATOR', origin: 'CALENDAR' });
  });
  it('вне календаря или без него тип неизвестен: чётность ISO-недели не используется', () => {
    expect(resolveWeekType('2026-09-14', calendar, null)).toEqual({ type: null, origin: 'UNKNOWN' });
    expect(resolveWeekType('2026-09-02', [], null)).toEqual({ type: null, origin: 'UNKNOWN' });
  });
  it('ручная настройка важнее календаря и чередуется от якоря в обе стороны', () => {
    const override: WeekOverride = { anchorWeekStart: '2026-11-23', type: 'DENOMINATOR' };
    expect(resolveWeekType('2026-11-25', calendar, override)).toEqual({ type: 'DENOMINATOR', origin: 'MANUAL' });
    expect(resolveWeekType('2026-12-02', calendar, override).type).toBe('NUMERATOR');
    expect(resolveWeekType('2026-12-09', calendar, override).type).toBe('DENOMINATOR');
    expect(resolveWeekType('2026-11-18', calendar, override).type).toBe('NUMERATOR');
    expect(resolveWeekType('2026-11-11', calendar, override).type).toBe('DENOMINATOR');
  });
  it('якорь в середине недели равен понедельнику той недели', () => {
    expect(resolveWeekType('2026-11-23', [], { anchorWeekStart: '2026-11-26', type: 'NUMERATOR' }).type).toBe('NUMERATOR');
  });
  it('повреждённая настройка игнорируется', () => {
    expect(parseWeekOverride('не json')).toBeNull();
    expect(parseWeekOverride('{"anchorWeekStart":"x","type":"NUMERATOR"}')).toBeNull();
    expect(parseWeekOverride('{"anchorWeekStart":"2026-11-23","type":"NUMERATOR"}')).toEqual({ anchorWeekStart: '2026-11-23', type: 'NUMERATOR' });
  });
});

describe('занятия на дату', () => {
  const entries = [
    entry({ id: 'every', subject: 'Каждую', dayOfWeek: 1, startTime: '09:50', endTime: '11:25' }),
    entry({ id: 'num', subject: 'Числ', dayOfWeek: 1, weekType: 'NUMERATOR', startTime: '08:00' }),
    entry({ id: 'den', subject: 'Знам', dayOfWeek: 1, weekType: 'DENOMINATOR', startTime: '08:00' }),
    entry({ id: 'sub1', subject: 'Лаб 1', dayOfWeek: 1, subgroupNumber: 1, startTime: '13:00', endTime: '14:35' }),
    entry({ id: 'sub2', subject: 'Лаб 2', dayOfWeek: 1, subgroupNumber: 2, startTime: '13:00', endTime: '14:35' }),
    entry({ id: 'tue', subject: 'Вторник', dayOfWeek: 2 }),
    entry({ id: 'dated', subject: 'Заочка', dayOfWeek: 1, specificDate: '2026-12-07' }),
  ];
  const ids = (date: string, week: 'NUMERATOR' | 'DENOMINATOR' | null, sub: number | null) => lessonsOn(entries, date, week, sub).map((l) => l.entry.id);

  it('в числитель нет «только знаменатель», и наоборот; порядок по времени', () => {
    expect(ids('2026-11-23', 'NUMERATOR', null)).toEqual(['num', 'every', 'sub1', 'sub2']);
    expect(ids('2026-11-23', 'DENOMINATOR', null)).toEqual(['den', 'every', 'sub1', 'sub2']);
  });
  it('неизвестный тип недели: занятия «числитель/знаменатель» остаются с пометкой, а не пропадают', () => {
    const lessons = lessonsOn(entries, '2026-11-23', null, null);
    // в одно время: по названию («Знам» раньше «Числ»)
    expect(lessons.map((l) => l.entry.id)).toEqual(['den', 'num', 'every', 'sub1', 'sub2']);
    expect(lessons.filter((l) => l.weekUncertain).map((l) => l.entry.id)).toEqual(['den', 'num']);
  });
  it('подгруппа отбирает свои занятия и общие', () => {
    expect(ids('2026-11-23', 'NUMERATOR', 1)).toEqual(['num', 'every', 'sub1']);
    expect(ids('2026-11-23', 'NUMERATOR', 2)).toEqual(['num', 'every', 'sub2']);
  });
  it('разовое занятие берётся по дате, а не по дню недели', () => {
    expect(ids('2026-12-07', 'DENOMINATOR', null)).toContain('dated');
    expect(ids('2026-11-30', 'NUMERATOR', null)).not.toContain('dated');
  });
  it('день без занятий пуст', () => {
    expect(ids('2026-11-28', 'NUMERATOR', null)).toEqual([]);
  });
});

describe('«сейчас» и «дальше»', () => {
  const lessons = lessonsOn(
    [entry({ id: 'a', startTime: '08:00', endTime: '09:35' }), entry({ id: 'b', startTime: '09:50', endTime: '11:25' }), entry({ id: 'c', startTime: '11:40', endTime: '13:15' })],
    '2026-11-23',
    null,
    null,
  );
  it('фазы по времени', () => {
    expect(phasesAt(lessons, 7 * 60)).toEqual(['NEXT', 'LATER', 'LATER']);
    expect(phasesAt(lessons, 8 * 60 + 30)).toEqual(['NOW', 'NEXT', 'LATER']);
    expect(phasesAt(lessons, 9 * 60 + 40)).toEqual(['PAST', 'NEXT', 'LATER']);
    expect(phasesAt(lessons, 14 * 60)).toEqual(['PAST', 'PAST', 'PAST']);
  });
  it('ближайший день с занятиями', () => {
    const entries = [entry({ dayOfWeek: 3 })];
    expect(nextLessonDay(entries, '2026-11-23', () => null, null)).toBe('2026-11-25');
    expect(nextLessonDay([], '2026-11-23', () => null, null)).toBeNull();
  });
  it('даты недели и номера подгрупп', () => {
    expect(weekDates('2026-11-26')[0]).toBe('2026-11-23');
    expect(weekDates('2026-11-26')[6]).toBe('2026-11-29');
    expect(subgroupNumbers([entry({ subgroupNumber: 3 }), entry({ subgroupNumber: 1 })], null)).toEqual([1, 2, 3]);
    expect(subgroupNumbers([entry({})], null)).toEqual([]);
  });
});
