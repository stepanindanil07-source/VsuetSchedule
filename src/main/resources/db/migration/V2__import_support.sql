-- Plain VARCHAR instead of CHAR(64) for hashes (no space padding, simple JPA mapping).
ALTER TABLE schedule_source ALTER COLUMN last_sha256 TYPE VARCHAR(64);
ALTER TABLE import_run ALTER COLUMN sha256 TYPE VARCHAR(64);

-- A source can be pinned to a period: the semester text inside a faculty file is not trusted
-- (e.g. eiu.xlsx says 2025-2026 although it is the 2026-2027 schedule). NULL = use the period named in the file.
ALTER TABLE schedule_source ADD COLUMN academic_period_id BIGINT REFERENCES academic_period (id);

-- Diagnostics are written and read as one document; TEXT keeps the JPA mapping trivial.
ALTER TABLE import_run ALTER COLUMN diagnostics TYPE TEXT USING diagnostics::text;
ALTER TABLE import_run ADD COLUMN period_id BIGINT REFERENCES academic_period (id);
ALTER TABLE import_run ADD COLUMN error_message TEXT;

-- Hash of the group's current lessons (incl. period); schedule_version is bumped only when it changes.
ALTER TABLE study_group ADD COLUMN content_hash VARCHAR(64);

-- The source that last saw the group. A unit can have several files (SPO: 1st course / 2nd-4th courses), so a group
-- missing from one file may only be hidden by the source that owns it, never by a sibling source of the same unit.
ALTER TABLE study_group ADD COLUMN source_id BIGINT REFERENCES schedule_source (id);
