# VSUET Schedule — backend

REST API with the class schedule of Voronezh State University of Engineering Technologies (VSUET).
The backend downloads the faculties' Excel files, parses them into one canonical model, stores the result in
PostgreSQL and serves it to the mobile client (React Native), which keeps its own offline copy.

## Run locally

Requirements: JDK 25, Docker.

```bash
export JAVA_HOME=<path to JDK 25>      # Maven may otherwise pick another JDK
docker compose up -d postgres          # PostgreSQL 17 on localhost:5433
./mvnw spring-boot:run
```

- API: <http://localhost:8080/api/v1/faculties> · Swagger UI: <http://localhost:8080/swagger-ui.html> ·
  OpenAPI: <http://localhost:8080/v3/api-docs>
- Flyway creates the schema and seeds the 7 faculties (5 grid `.xlsx`, SPO `.xls`, correspondence `.xlsx`), their file URLs and the autumn 2026–2027 semester.
- Sources are imported every hour. Change it with `VSUET_IMPORT_CRON` (e.g. `"0/30 * * * * *"` = every 30 s) or
  switch it off with `VSUET_IMPORT_SCHEDULER_ENABLED=false`.
- Database settings: `DB_URL`, `DB_USER`, `DB_PASSWORD`.
- Administrative API: set `VSUET_ADMIN_TOKEN` (any long random string). Without it `/api/v1/admin/**` is switched off
  and answers 404 as if it did not exist.

Tests (need Docker for Testcontainers): `./mvnw verify`.

## Endpoints (`/api/v1`)

| Endpoint | Purpose |
|---|---|
| `GET /faculties` | faculties |
| `GET /faculties/{id}/groups` | groups present in the current schedule, with `course` (null when the file does not say) |
| `GET /groups/{id}/schedule?weekType=&subgroup=` | schedule of the current semester |
| `GET /groups/{id}/schedule/version` | version of the group's schedule (ETag, 304) |

Errors are RFC 9457 `application/problem+json`.

### Administrative API (`/api/v1/admin`, header `Authorization: Bearer <VSUET_ADMIN_TOKEN>`)

| Endpoint | Purpose |
|---|---|
| `GET /periods` · `POST /periods` · `PUT /periods/{id}` | semesters and the week-parity anchor |
| `GET /sources` | the schedule files: unit, parser, pinned semester, latest import |
| `PUT /sources/{id}/period` · `PUT /sources/{id}/enabled` | pin a file to a semester (or unpin) · switch it on/off |
| `POST /sources/{id}/import` · `POST /imports` | download and import one file / every enabled file now |
| `GET /sources/{id}/runs` · `GET /import-runs/{runId}/diagnostics` | import history · what the parser found |

A new semester is registered with `POST /periods` **before** its files can be imported (`firstWeekStart` must be a
Monday and contain the first day of the semester; dates must not overlap another semester). Then re-pin the files
that are pinned to the old one (`eiu.xlsx`, both SPO files, the correspondence file): the next import re-reads them
even if the file did not change. Changing the anchor of an existing semester raises the schedule version of all its
groups, so cached apps reload the new parity. Full request/response shapes: Swagger UI.

## How it works

```
schedule_source (URL + parser key)
   │ ScheduleFileFetcher         timeouts, retries, size limit
   ▼
ImportService                    SHA-256 → unchanged? skip
   │ ParserRegistry → ScheduleParser (vsuet-grid-xlsx = FuitsExcelParser)
   ▼
ParsedSchedule (canonical model + diagnostics)        ← no database here
   │ ScheduleImportApplier        ONE transaction; failure = rollback, old schedule stays
   ▼
study_group (version per group) · schedule_entry · import_run (history)
```

- **Week parity** is not in the files: `academic_period.first_week_start` (a Monday) and `first_week_type` are set
  by the university; every other week follows by alternating. The schedule response carries this anchor.
- **A new semester** needs a row in `academic_period` before its files can be imported. A source can be pinned to a
  period (`schedule_source.academic_period_id`) when the semester text inside its file is wrong (eiu.xlsx is).
- **Parsers:** `vsuet-grid-xlsx` (uits, itf, eht, tf, eiu) and `spo-xls` (fspo/1_kurs.xls, fspo/2-3-4_kurs.xls) share
  one grid engine (`importer/parser/grid`: header, groups, days, slots, merged-cell geometry) and differ only in the
  cell-text notation, time notation and a few policies (`GridFormat`). A new layout = a new `ScheduleParser` with its
  own key; nothing else changes.
- **Correspondence (заочное) schedule** (`zao-xlsx`, zao/5_kurs.xlsx) is a different layout and has its own parser:
  lessons are listed per **calendar date** ("ПНД 12/10"), not per weekday and week parity, so a lesson may carry a
  `date` (API: `lessons[].date`, `weekType` is `EVERY_WEEK`; null for the usual weekly lessons) and can be an
  `EXAM`/`CREDIT`. The file has no year, semester or course: the source must be pinned to a period (the year is the
  first one that puts the date on or after the period start, and the weekday written next to the date is checked
  against it), and `schedule_source.default_course` gives the groups their course. Two teachers/rooms or two parallel
  subjects of one cell stay one lesson showing both.
- **Unchanged files** (same SHA-256) are skipped only if they were imported by the *current version of their parser*
  (`ScheduleParser.version()`); bump it whenever a parser's output for the same file can change, and the next import
  re-reads the file (groups whose lessons did not change keep their schedule version).
- **Semester not stated in a file** (2-3-4_kurs.xls, zao/5_kurs.xlsx): the source must be pinned to a period, otherwise the import is
  rejected before anything is written.
- **Course of a group** is read from the sheet: a merged band above the groups ("2 КУРС" in 2-3-4_kurs.xls) wins;
  otherwise the sheet name and title, if they name exactly one course ("1 курс", "учебных занятий 1 курса"). A sheet
  that names several courses ("1, 2 и 5 курсов": masters and 5th-year bachelors) does not say which group is which, so
  the admission year in the group code decides ("Ум-261" = admitted 2026 = course 1 in 2026-2027; "УБ-21" = 2022 =
  course 5) **only if the sheet's title names that course**; otherwise the course stays empty with a
  `GROUP_COURSE_UNKNOWN` warning. A derived course is reported once per sheet (`COURSE_FROM_GROUP_CODE`). The academic
  year comes from the period the source is pinned to (eiu.xlsx states its semester wrongly). On the 245 groups whose
  sheets state the course, the code rule agrees every time. Masters have only courses 1 and 2.
- **Hidden columns and rows are not read.** The SPO files keep stale groups and lessons in columns hidden in Excel
  (2-3-4_kurs.xls: 8 groups in D:R, plus extra columns of 4-column group headers; 1_kurs.xls: 12 hidden columns).
  What Excel does not show is not part of the schedule: groups with no visible column are dropped, a lesson merged over
  hidden columns counts for the visible ones only, subgroups are numbered among visible columns. Ignored cells are
  never silent: `HIDDEN_GROUPS_IGNORED` / `HIDDEN_CELLS_IGNORED` warnings carry the counts.
- Module layout: `academic` (faculties, groups, periods), `schedule` (lessons, read API), `importer`
  (sources, parsers, import), `common`.

See `src/main/java/.../importer/parser/grid/GridSheetParser.java` for how the sheet geometry (merged cells) is
turned into week type and subgroup.
