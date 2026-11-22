-- Faculty of Secondary Vocational Education (SPO): two .xls files with their own layout (parser "spo-xls").
-- 2-3-4_kurs.xls does not state its semester, so both sources are pinned to autumn 2026-2027 (1_kurs.xls states the
-- same semester; pinning it too keeps the two files consistent).
INSERT INTO organization_unit (code, name) VALUES
    ('fspo', 'Факультет среднего профессионального образования');

INSERT INTO schedule_source (unit_id, parser_key, url, enabled, academic_period_id)
SELECT u.id, 'spo-xls', f.url, TRUE,
       (SELECT id FROM academic_period WHERE year_from = 2026 AND term = 'AUTUMN')
FROM organization_unit u
CROSS JOIN (VALUES
    ('https://vsuet.ru/images/student/schedule/fspo/1_kurs.xls'),
    ('https://vsuet.ru/images/student/schedule/fspo/2-3-4_kurs.xls')) AS f(url)
WHERE u.code = 'fspo';
