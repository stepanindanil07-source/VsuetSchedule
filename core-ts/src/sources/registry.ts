import type { AcademicPeriod, KnownGap, OrganizationUnit, ScheduleSource } from './types';

/**
 * Реестр источников расписания ВГУИТ на осенний семестр 2026–2027. Составлен по официальной странице
 * https://vsuet.ru/student/schedule 2026-11-23; подробности и статус исследования см. docs/SOURCE_REGISTRY.md.
 *
 * ПОДКЛЮЧЕНО 8 из 15 опубликованных файлов. Поддержка всего университета НЕ реализована: остальные 7 перечислены ниже как пробелы
 * полноты и попадают в manifest набора (coverage.knownGaps), пока по каждому не принято решение подключить его или исключить.
 */

const BASE = 'https://vsuet.ru/images/student/schedule/';

export const AUTUMN_2026: AcademicPeriod = {
  id: '2026-autumn',
  academicYear: '2026-2027',
  yearFrom: 2026,
  term: 'AUTUMN',
  startDate: '2026-09-01',
  endDate: '2026-12-31',
  // Неделя с 31.08.2026 (понедельник), в которую начинается семестр, это числитель; дальше недели чередуются.
  // Введено вручную по указанию владельца проекта, университетом как данные не опубликовано.
  calendar: { firstWeekStart: '2026-08-31', firstWeekType: 'NUMERATOR', source: 'MANUAL' },
};

export const ORGANIZATION_UNITS: readonly OrganizationUnit[] = [
  { id: 'uits', name: 'Факультет управления и информатики в технологических системах', type: 'FACULTY' },
  { id: 'itf', name: 'Инженерно-технический факультет', type: 'FACULTY' },
  { id: 'eht', name: 'Факультет экологии и химической технологии', type: 'FACULTY' },
  { id: 'tf', name: 'Технологический факультет', type: 'FACULTY' },
  { id: 'eiu', name: 'Факультет экономики и управления', type: 'FACULTY' },
  { id: 'fspo', name: 'Факультет среднего профессионального образования', type: 'COLLEGE' },
  { id: 'zao', name: 'Заочная форма обучения', type: 'OTHER' },
];

function grid(unit: string, file: string, name: string, periodId: string | null = null): ScheduleSource {
  return { id: unit, organizationUnitId: unit, name, url: BASE + file, format: 'XLSX', parserId: 'vsuet-grid-xlsx', required: true, enabled: true, periodId, defaultCourse: null };
}

export const SOURCES: readonly ScheduleSource[] = [
  grid('uits', 'uits.xlsx', 'ФУИТС: 1–5 курсы и магистры'),
  grid('itf', 'itf.xlsx', 'ИТФ: 1–4 курсы и магистры'),
  grid('eht', 'eht.xlsx', 'ЭХТ: 1–4 курсы и магистры'),
  grid('tf', 'tf.xlsx', 'ТФ: 1–4 курсы и магистры'),
  // В тексте файла ошибочно «осеннего семестра 2025-2026»: привязан к периоду вручную.
  { ...grid('eiu', 'eiu.xlsx', 'ЭУ: 1–5 курсы и магистры', AUTUMN_2026.id) },
  {
    id: 'fspo-1',
    organizationUnitId: 'fspo',
    name: 'СПО: 1 курс',
    url: BASE + 'fspo/1_kurs.xls',
    format: 'XLS',
    parserId: 'spo-xls',
    required: true,
    enabled: true,
    periodId: AUTUMN_2026.id,
    defaultCourse: null,
  },
  // Семестра в файле нет: привязка к периоду обязательна.
  {
    id: 'fspo-234',
    organizationUnitId: 'fspo',
    name: 'СПО: 2–4 курсы',
    url: BASE + 'fspo/2-3-4_kurs.xls',
    format: 'XLS',
    parserId: 'spo-xls',
    required: true,
    enabled: true,
    periodId: AUTUMN_2026.id,
    defaultCourse: null,
  },
  // Семестра, курса и года в датах в файле нет: привязка к периоду обязательна, курс задаёт источник.
  {
    id: 'zao-5',
    organizationUnitId: 'zao',
    name: 'Заочная форма: 5 курс',
    url: BASE + 'zao/5_kurs.xlsx',
    format: 'XLSX',
    parserId: 'zao-xlsx',
    required: true,
    enabled: true,
    periodId: AUTUMN_2026.id,
    defaultCourse: 5,
  },
];

/** Опубликованные университетом файлы, которые ещё не подключены. Три очно-заочных файла не менялись с ноября 2023 года. */
export const KNOWN_GAPS: readonly KnownGap[] = [
  { url: BASE + 'ocz/eht_mag.xlsx', name: 'ЭХТ: магистры (очно-заочное)', reason: 'NOT_CONNECTED: не исследован; файл изменён 2023-11-10, возможно устарел' },
  { url: BASE + 'ocz/tf_1.xlsx', name: 'ТФ: 1 курс (очно-заочное)', reason: 'NOT_CONNECTED: не исследован; файл изменён 2023-11-17, возможно устарел' },
  { url: BASE + 'ocz/tf_2.xlsx', name: 'ТФ: 2 курс (очно-заочное)', reason: 'NOT_CONNECTED: не исследован; файл изменён 2023-11-08, возможно устарел' },
  { url: BASE + 'ocz/tf_mag.xlsx', name: 'ТФ: магистры (очно-заочное)', reason: 'NOT_CONNECTED: не исследован; файл изменён 2023-11-08, возможно устарел' },
  { url: BASE + 'zao/2_kurs_mag.xlsx', name: 'Заочная форма: 2 курс магистратуры', reason: 'NOT_CONNECTED: не исследован' },
  { url: 'https://vsuet.ru/images/science/aspir/docs/rasp/asp.xlsx', name: 'Аспирантура', reason: 'NOT_CONNECTED: не исследован; файл изменён 2026-02-16' },
  { url: BASE + 'fspo/raboch_prof.xls', name: 'СПО: рабочие профессии', reason: 'NOT_CONNECTED: не исследован' },
];
