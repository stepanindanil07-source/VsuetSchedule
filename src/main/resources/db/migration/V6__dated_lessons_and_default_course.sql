-- A lesson may be tied to one calendar date instead of repeating weekly: correspondence (zaochnoe) students have
-- sessions on specific dates. NULL = the usual weekly lesson (day_of_week + week_type).
ALTER TABLE schedule_entry ADD COLUMN lesson_date DATE;
ALTER TABLE schedule_entry ADD CONSTRAINT ck_entry_dated_every_week
    CHECK (lesson_date IS NULL OR week_type = 'EVERY_WEEK');
ALTER TABLE schedule_entry ADD CONSTRAINT ck_entry_date_matches_day
    CHECK (lesson_date IS NULL OR EXTRACT(ISODOW FROM lesson_date) = day_of_week);

-- Exams and credits are written in the correspondence schedule ("экз.", "зач.").
ALTER TABLE schedule_entry DROP CONSTRAINT schedule_entry_lesson_type_check;
ALTER TABLE schedule_entry ADD CONSTRAINT schedule_entry_lesson_type_check
    CHECK (lesson_type IN ('LECTURE', 'PRACTICE', 'LAB', 'EXAM', 'CREDIT', 'OTHER'));

-- Course of every group of a file that does not state it (zao/5_kurs.xlsx is the file of the 5th course).
-- A course stated in the file itself wins.
ALTER TABLE schedule_source ADD COLUMN default_course SMALLINT CHECK (default_course BETWEEN 1 AND 6);
