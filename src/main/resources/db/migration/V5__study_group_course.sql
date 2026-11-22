-- Year of study as the schedule file states it. Empty when the file does not say it unambiguously
-- (a sheet "1, 2 и 5 курсов" mixes courses; SPO groups that stand under no "N КУРС" band).
ALTER TABLE study_group
    ADD COLUMN course SMALLINT CHECK (course BETWEEN 1 AND 6);
