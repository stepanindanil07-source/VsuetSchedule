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
- Flyway creates the schema and seeds the 5 faculties, their file URLs and the autumn 2026–2027 semester.
- Sources are imported every hour. Change it with `VSUET_IMPORT_CRON` (e.g. `"0/30 * * * * *"` = every 30 s) or
  switch it off with `VSUET_IMPORT_SCHEDULER_ENABLED=false`.
- Database settings: `DB_URL`, `DB_USER`, `DB_PASSWORD`.

Tests (need Docker for Testcontainers): `./mvnw verify`.

## Endpoints (`/api/v1`)

| Endpoint | Purpose |
|---|---|
| `GET /faculties` | faculties |
| `GET /faculties/{id}/groups` | groups present in the current schedule |
| `GET /groups/{id}/schedule?weekType=&subgroup=` | schedule of the current semester |
| `GET /groups/{id}/schedule/version` | version of the group's schedule (ETag, 304) |

Errors are RFC 9457 `application/problem+json`.

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
- **A new layout** (SPO `.xls`, correspondence courses) = a new `ScheduleParser` implementation with its own key;
  nothing else changes.
- Module layout: `academic` (faculties, groups, periods), `schedule` (lessons, read API), `importer`
  (sources, parsers, import), `common`.

See `src/main/java/.../importer/parser/fuits/FuitsSheetParser.java` for how the sheet geometry (merged cells) is
turned into week type and subgroup.
