-- Reference data: the faculties whose schedules share the grid layout (parser "vsuet-grid-xlsx") and their files.
-- Correspondence courses (zao/5_kurs.xlsx) and SPO (fspo/*.xls) have other layouts and are added with their own parsers.
INSERT INTO organization_unit (code, name) VALUES
    ('uits', 'Факультет управления и информатики в технологических системах'),
    ('itf',  'Инженерно-технический факультет'),
    ('eht',  'Факультет экологии и химической технологии'),
    ('tf',   'Технологический факультет'),
    ('eiu',  'Факультет экономики и управления');

-- Autumn 2026-2027. The first study day is Tuesday 1 Sep 2026, so week 1 starts on Monday 31 Aug and is a
-- numerator week (set by the university). The end date is an assumption (end of January) and can be edited.
INSERT INTO academic_period (year_from, year_to, term, start_date, end_date, first_week_start, first_week_type)
VALUES (2026, 2027, 'AUTUMN', DATE '2026-09-01', DATE '2027-01-31', DATE '2026-08-31', 'NUMERATOR');

INSERT INTO schedule_source (unit_id, parser_key, url, enabled)
SELECT u.id, 'vsuet-grid-xlsx', 'https://vsuet.ru/images/student/schedule/' || u.code || '.xlsx', TRUE
FROM organization_unit u;

-- The semester text inside eiu.xlsx says 2025-2026 (a typo confirmed by the university): pin it to the real one.
UPDATE schedule_source
SET academic_period_id = (SELECT id FROM academic_period WHERE year_from = 2026 AND term = 'AUTUMN')
WHERE unit_id = (SELECT id FROM organization_unit WHERE code = 'eiu');
