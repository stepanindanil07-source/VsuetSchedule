-- Correspondence (zaochnoe) schedule of the 5th course: one .xlsx with a lesson list per calendar date (parser
-- "zao-xlsx"). The file states neither the semester nor the course, so the source is pinned to autumn 2026-2027 and
-- every group gets course 5. The unit's display name is an assumption: the university names the folder only "zao".
INSERT INTO organization_unit (code, name) VALUES
    ('zao', 'Заочная форма обучения');

INSERT INTO schedule_source (unit_id, parser_key, url, enabled, academic_period_id, default_course)
SELECT u.id, 'zao-xlsx', 'https://vsuet.ru/images/student/schedule/zao/5_kurs.xlsx', TRUE,
       (SELECT id FROM academic_period WHERE year_from = 2026 AND term = 'AUTUMN'), 5
FROM organization_unit u
WHERE u.code = 'zao';
