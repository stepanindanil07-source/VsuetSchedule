-- Faculty / organisation unit that publishes a schedule.
CREATE TABLE organization_unit (
    id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code VARCHAR(32)  NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL
);

-- A semester. The week-parity anchor is entered manually by the university staff:
-- first_week_start is the Monday of the first week, first_week_type is its parity.
-- Parity of any other date is derived from these two fields (weeks alternate).
CREATE TABLE academic_period (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    year_from       SMALLINT    NOT NULL,
    year_to         SMALLINT    NOT NULL,
    term            VARCHAR(16) NOT NULL CHECK (term IN ('AUTUMN', 'SPRING')),
    start_date      DATE        NOT NULL,
    end_date        DATE        NOT NULL,
    first_week_start DATE       NOT NULL,
    first_week_type VARCHAR(16) NOT NULL CHECK (first_week_type IN ('NUMERATOR', 'DENOMINATOR')),
    CONSTRAINT uq_academic_period UNIQUE (year_from, term),
    CONSTRAINT ck_period_dates CHECK (start_date <= end_date),
    CONSTRAINT ck_first_week_is_monday CHECK (EXTRACT(ISODOW FROM first_week_start) = 1)
);

CREATE TABLE schedule_source (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    unit_id         BIGINT       NOT NULL REFERENCES organization_unit (id),
    parser_key      VARCHAR(64)  NOT NULL,
    url             VARCHAR(512) NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    last_sha256     CHAR(64),
    last_checked_at TIMESTAMPTZ
);

-- Groups are matched by (unit, code) and are never deleted by an import: a group
-- missing from a file is only flagged inactive, so client-side ids stay stable.
CREATE TABLE study_group (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    unit_id             BIGINT       NOT NULL REFERENCES organization_unit (id),
    code                VARCHAR(64)  NOT NULL,
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    schedule_version    BIGINT       NOT NULL DEFAULT 0,
    schedule_updated_at TIMESTAMPTZ,
    CONSTRAINT uq_study_group UNIQUE (unit_id, code)
);

CREATE TABLE import_run (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id      BIGINT      NOT NULL REFERENCES schedule_source (id),
    started_at     TIMESTAMPTZ NOT NULL,
    finished_at    TIMESTAMPTZ,
    status         VARCHAR(24) NOT NULL CHECK (status IN ('RUNNING', 'SUCCESS', 'SKIPPED_UNCHANGED', 'FAILED')),
    sha256         CHAR(64),
    parser_version VARCHAR(32),
    total_found    INT,
    recognized     INT,
    skipped        INT,
    diagnostics    JSONB
);

CREATE TABLE schedule_entry (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id      BIGINT       NOT NULL REFERENCES study_group (id),
    period_id     BIGINT       NOT NULL REFERENCES academic_period (id),
    import_run_id BIGINT       NOT NULL REFERENCES import_run (id),
    day_of_week   SMALLINT     NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    start_time    TIME         NOT NULL,
    end_time      TIME         NOT NULL,
    week_type     VARCHAR(16)  NOT NULL CHECK (week_type IN ('NUMERATOR', 'DENOMINATOR', 'EVERY_WEEK')),
    subgroup      SMALLINT     CHECK (subgroup > 0),   -- NULL = whole group
    subject       VARCHAR(512) NOT NULL,
    lesson_type   VARCHAR(16)  NOT NULL CHECK (lesson_type IN ('LECTURE', 'PRACTICE', 'LAB', 'OTHER')),
    teacher_text  VARCHAR(512),                          -- may hold several teachers
    room_text     VARCHAR(255),                          -- may hold several rooms
    raw_text      TEXT         NOT NULL,                 -- original cell text, for diagnostics
    source_sheet  VARCHAR(128) NOT NULL,
    source_range  VARCHAR(32)  NOT NULL,
    CONSTRAINT ck_entry_time CHECK (start_time < end_time)
);

CREATE INDEX idx_schedule_entry_group_period ON schedule_entry (group_id, period_id);
