package ru.vsuet.schedule.importer.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * What the Flyway migrations leave in a brand-new database: the faculties, their files and the semester. The other
 * integration tests wipe these tables, so this one runs the migrations on its own container.
 */
class SeedMigrationsTest {

    private static PostgreSQLContainer postgres;
    private static JdbcClient jdbc;

    @BeforeAll
    static void migrateEmptyDatabase() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
        postgres.start();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    @AfterAll
    static void stop() {
        postgres.stop();
    }

    @Test
    void sevenFacultiesAreSeeded() {
        List<String> codes = jdbc.sql("SELECT code FROM organization_unit ORDER BY code").query(String.class).list();

        assertThat(codes).containsExactly("eht", "eiu", "fspo", "itf", "tf", "uits", "zao");
    }

    @Test
    void nineFilesAreSeededEachWithItsParser() {
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT u.code AS unit, s.parser_key AS parser, count(*) AS files
                FROM schedule_source s JOIN organization_unit u ON u.id = s.unit_id
                GROUP BY u.code, s.parser_key ORDER BY u.code""").query().listOfRows();

        assertThat(rows).extracting(r -> r.get("unit") + ":" + r.get("parser") + ":" + r.get("files")).containsExactly(
                "eht:vsuet-grid-xlsx:1", "eiu:vsuet-grid-xlsx:1", "fspo:spo-xls:2", "itf:vsuet-grid-xlsx:1",
                "tf:vsuet-grid-xlsx:1", "uits:vsuet-grid-xlsx:1", "zao:zao-xlsx:1");
    }

    @Test
    void autumn2026StartsOnTheFirstOfSeptemberAndEndsOnTheLastDayOfTheYear() {
        Map<String, Object> period = jdbc.sql("SELECT start_date, end_date, first_week_start, first_week_type FROM academic_period "
                + "WHERE year_from = 2026 AND term = 'AUTUMN'").query().singleRow();

        assertThat(period.get("start_date").toString()).isEqualTo("2026-09-01");
        assertThat(period.get("end_date").toString()).isEqualTo("2026-12-31");
        assertThat(period.get("first_week_start").toString()).isEqualTo("2026-08-31");
        assertThat(period.get("first_week_type")).isEqualTo("NUMERATOR");
    }

    @Test
    void correspondenceSourceIsPinnedToAutumn2026AndKnowsItIsTheFifthCourse() {
        Map<String, Object> zao = jdbc.sql("""
                SELECT s.url, s.default_course, p.year_from, p.term
                FROM schedule_source s JOIN academic_period p ON p.id = s.academic_period_id
                WHERE s.parser_key = 'zao-xlsx'""").query().singleRow();

        assertThat(zao.get("url")).isEqualTo("https://vsuet.ru/images/student/schedule/zao/5_kurs.xlsx");
        assertThat(((Number) zao.get("default_course")).intValue()).isEqualTo(5);
        assertThat(((Number) zao.get("year_from")).intValue()).isEqualTo(2026);
        assertThat(zao.get("term")).isEqualTo("AUTUMN");
    }

    @Test
    void everyFileThatNeedsAPinnedPeriodHasOne() {
        // 2-3-4_kurs.xls and zao/5_kurs.xlsx state no semester, eiu.xlsx states a wrong one
        List<String> pinned = jdbc.sql("""
                SELECT s.url FROM schedule_source s WHERE s.academic_period_id IS NOT NULL ORDER BY s.url""")
                .query(String.class).list();

        assertThat(pinned).anyMatch(u -> u.endsWith("fspo/2-3-4_kurs.xls")).anyMatch(u -> u.endsWith("zao/5_kurs.xlsx"))
                .anyMatch(u -> u.endsWith("eiu.xlsx"));
    }

    @Test
    void databaseRejectsADatedLessonThatDisagreesWithItsWeekdayOrParity() {
        jdbc.sql("INSERT INTO organization_unit (code, name) VALUES ('x', 'x')").update();
        jdbc.sql("INSERT INTO schedule_source (unit_id, parser_key, url) SELECT id, 'k', 'u' FROM organization_unit WHERE code = 'x'").update();
        jdbc.sql("INSERT INTO import_run (source_id, started_at, status) SELECT id, now(), 'SUCCESS' FROM schedule_source WHERE url = 'u'").update();
        jdbc.sql("INSERT INTO study_group (unit_id, code, active, schedule_version) SELECT id, 'G', true, 0 FROM organization_unit WHERE code = 'x'").update();
        String insert = """
                INSERT INTO schedule_entry (group_id, period_id, import_run_id, day_of_week, start_time, end_time, week_type,
                    subject, lesson_type, raw_text, source_sheet, source_range, lesson_date)
                SELECT g.id, p.id, r.id, :day, '08:00', '09:35', :week, 's', 'EXAM', 'r', 'sh', 'A1', DATE '2026-10-12'
                FROM study_group g, academic_period p, import_run r WHERE g.code = 'G' AND p.year_from = 2026 AND p.term = 'AUTUMN'""";

        // 2026-10-12 is a Monday (ISO day 1)
        assertThat(jdbc.sql(insert).param("day", 1).param("week", "EVERY_WEEK").update()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql(insert).param("day", 2).param("week", "EVERY_WEEK").update())
                .hasMessageContaining("ck_entry_date_matches_day");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql(insert).param("day", 1).param("week", "NUMERATOR").update())
                .hasMessageContaining("ck_entry_dated_every_week");
    }
}
