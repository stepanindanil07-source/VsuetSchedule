package ru.vsuet.schedule.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.vsuet.schedule.TestcontainersConfiguration;
import ru.vsuet.schedule.academic.domain.OrganizationUnit;
import ru.vsuet.schedule.academic.domain.StudyGroup;
import ru.vsuet.schedule.academic.infrastructure.OrganizationUnitRepository;
import ru.vsuet.schedule.academic.infrastructure.StudyGroupRepository;
import ru.vsuet.schedule.importer.application.ImportResult;
import ru.vsuet.schedule.importer.application.ImportService;
import ru.vsuet.schedule.importer.domain.ImportStatus;
import ru.vsuet.schedule.importer.domain.ScheduleSource;
import ru.vsuet.schedule.importer.infrastructure.ScheduleSourceRepository;
import ru.vsuet.schedule.importer.parser.fuits.FuitsExcelParser;
import ru.vsuet.schedule.importer.parser.fuits.WorkbookFixture;
import ru.vsuet.schedule.schedule.infrastructure.ScheduleEntryRepository;

/**
 * Rehearsal of the winter break: the autumn semester runs, the spring files appear, staff register the new semester and
 * re-pin the files, and the app switches on the first day of the new semester. It encodes the runbook (docs/OPERATIONS.md)
 * step by step, including the two traps: a file for a semester that is not registered yet, and a pinned file (eiu.xlsx
 * states its semester wrongly) that would silently keep landing in the old semester until it is re-pinned.
 */
@SpringBootTest(properties = {"vsuet.admin.token=rollover-token", "vsuet.import.scheduler.enabled=false"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, SemesterRolloverIntegrationTest.MovableClock.class})
class SemesterRolloverIntegrationTest {

    /** A clock the test can set; {@code AcademicCalendar} asks it for "today" on every request. */
    static final class SettableClock extends Clock {
        private volatile Instant now;

        void set(String instant) {
            this.now = Instant.parse(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MovableClock {
        @Bean
        @Primary
        SettableClock settableClock() {
            SettableClock clock = new SettableClock();
            clock.set("2027-01-15T09:00:00Z");
            return clock;
        }
    }

    @Autowired MockMvc mvc;
    @Autowired SettableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired ImportService importService;
    @Autowired OrganizationUnitRepository units;
    @Autowired ScheduleSourceRepository sources;
    @Autowired StudyGroupRepository groups;
    @Autowired ScheduleEntryRepository entries;

    private ScheduleSource uits;   // states its semester correctly: the semester in the file decides
    private ScheduleSource eiu;    // states it wrongly: pinned to a semester by staff

    private static MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer rollover-token");
    }

    private ResultActions postJson(String url, String json) throws Exception {
        return mvc.perform(admin(post(url)).contentType(APPLICATION_JSON).content(json));
    }

    private static byte[] file(String semesterText, String group, String subject) {
        WorkbookFixture f = WorkbookFixture.xlsx().sheet("1 курс", semesterText, group, 1)
                .day("ПОНЕДЕЛЬНИК").slot("08.00-09.35", 2).put("C3", "пр." + subject + "\nИванов И.И.   12");
        try (InputStream in = f.stream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private long groupId(String code) {
        return groups.findAll().stream().filter(g -> g.getCode().equals(code)).findFirst().map(StudyGroup::getId).orElseThrow();
    }

    private ResultActions schedule(String groupCode) throws Exception {
        return mvc.perform(get("/api/v1/groups/{id}/schedule", groupId(groupCode)));
    }

    @BeforeEach
    void autumnIsRunning() throws Exception {
        jdbc.execute("TRUNCATE schedule_entry, import_run, study_group, schedule_source, academic_period, "
                + "organization_unit RESTART IDENTITY CASCADE");
        clock.set("2027-01-15T09:00:00Z");
        OrganizationUnit unitA = units.save(new OrganizationUnit("uits", "ФУИТС"));
        OrganizationUnit unitB = units.save(new OrganizationUnit("eiu", "ФЭУ"));
        uits = sources.save(new ScheduleSource(unitA.getId(), FuitsExcelParser.KEY, "http://example.test/uits.xlsx"));
        eiu = sources.save(new ScheduleSource(unitB.getId(), FuitsExcelParser.KEY, "http://example.test/eiu.xlsx"));

        // 31 Aug 2026 is a Monday and the first week of the autumn semester that starts on Tuesday 1 Sep
        postJson("/api/v1/admin/periods", periodJson("AUTUMN", 2026, "2026-09-01", "2027-01-31", "2026-08-31", "NUMERATOR"))
                .andExpect(status().isCreated());
        Long autumnId = jdbc.queryForObject("select id from academic_period where term = 'AUTUMN'", Long.class);
        mvc.perform(admin(put("/api/v1/admin/sources/{id}/period", eiu.getId())).contentType(APPLICATION_JSON)
                .content("{\"periodId\":" + autumnId + "}")).andExpect(status().isOk());

        assertThat(importService.importFile(uits.getId(), file("осеннего семестра 2026-2027 учебного года", "У-251", "Осенняя физика")).status())
                .isEqualTo(ImportStatus.SUCCESS);
        // eiu.xlsx-like: the file says 2025-2026 although it is the current schedule, so the source is pinned
        assertThat(importService.importFile(eiu.getId(), file("осеннего семестра 2025-2026 учебного года", "ЭБ-61", "Осенняя экономика")).status())
                .isEqualTo(ImportStatus.SUCCESS);
    }

    private static String periodJson(String term, int yearFrom, String start, String end, String firstWeek, String type) {
        return """
                {"term":"%s","yearFrom":%d,"startDate":"%s","endDate":"%s","firstWeekStart":"%s","firstWeekType":"%s"}"""
                .formatted(term, yearFrom, start, end, firstWeek, type);
    }

    @Test
    void theWholeWinterBreakFromTheLastAutumnWeekToTheFirstSpringDay() throws Exception {
        // 1. mid-January: the autumn schedule is shown
        schedule("У-251").andExpect(jsonPath("$.period.term").value("AUTUMN"))
                .andExpect(jsonPath("$.lessons[0].subject").value("Осенняя физика"));
        long versionBefore = groups.findById(groupId("У-251")).orElseThrow().getScheduleVersion();

        // 2. the university publishes the spring file BEFORE staff registered the semester: refused, nothing is damaged
        byte[] springFile = file("весеннего семестра 2026-2027 учебного года", "У-251", "Весенняя физика");
        ImportResult early = importService.importFile(uits.getId(), springFile);
        assertThat(early.status()).isEqualTo(ImportStatus.FAILED);
        assertThat(early.message()).contains("not registered");
        schedule("У-251").andExpect(jsonPath("$.lessons[0].subject").value("Осенняя физика"));
        assertThat(groups.findById(groupId("У-251")).orElseThrow().getScheduleVersion()).isEqualTo(versionBefore);

        // 3. staff register the spring semester (Monday 8 Feb 2027 starts the first week; the first week is a denominator week)
        postJson("/api/v1/admin/periods", periodJson("SPRING", 2026, "2027-02-08", "2027-06-30", "2027-02-08", "DENOMINATOR"))
                .andExpect(status().isCreated());
        Long springId = jdbc.queryForObject("select id from academic_period where term = 'SPRING'", Long.class);

        // 4. the unpinned file now imports into the spring semester by its own text; the app still shows autumn (not started)
        assertThat(importService.importFile(uits.getId(), springFile).status()).isEqualTo(ImportStatus.SUCCESS);
        schedule("У-251").andExpect(jsonPath("$.period.term").value("AUTUMN"))
                .andExpect(jsonPath("$.lessons[0].subject").value("Осенняя физика"));
        assertThat(groups.findById(groupId("У-251")).orElseThrow().getScheduleVersion())
                .as("the group's content changed: apps will reload").isGreaterThan(versionBefore);

        // 5. THE TRAP: the pinned eiu file is re-published for spring, but it still says autumn and is still pinned to
        //    the autumn semester, so its new lessons land in the OLD semester
        byte[] eiuSpring = file("осеннего семестра 2025-2026 учебного года", "ЭБ-61", "Весенняя экономика");
        assertThat(importService.importFile(eiu.getId(), eiuSpring).status()).isEqualTo(ImportStatus.SUCCESS);
        assertThat(jdbc.queryForObject("select count(*) from schedule_entry e join academic_period p on p.id = e.period_id "
                + "where p.term = 'SPRING' and e.subject = 'Весенняя экономика'", Long.class)).isZero();

        // 6. the runbook step: re-pin to the spring semester; the very same file is imported again (not skipped)
        mvc.perform(admin(put("/api/v1/admin/sources/{id}/period", eiu.getId())).contentType(APPLICATION_JSON)
                .content("{\"periodId\":" + springId + "}")).andExpect(status().isOk());
        assertThat(importService.importFile(eiu.getId(), eiuSpring).status()).isEqualTo(ImportStatus.SUCCESS);
        assertThat(jdbc.queryForObject("select count(*) from schedule_entry e join academic_period p on p.id = e.period_id "
                + "where p.term = 'SPRING' and e.subject = 'Весенняя экономика'", Long.class)).isEqualTo(1L);

        // 7. the first day of the spring semester: both groups switch, the parity follows the registered anchor
        clock.set("2027-02-09T09:00:00Z");   // Tuesday of the first spring week
        schedule("У-251").andExpect(jsonPath("$.period.term").value("SPRING"))
                .andExpect(jsonPath("$.currentWeekType").value("DENOMINATOR"))
                .andExpect(jsonPath("$.lessons[0].subject").value("Весенняя физика"));
        schedule("ЭБ-61").andExpect(jsonPath("$.period.term").value("SPRING"))
                .andExpect(jsonPath("$.lessons[0].subject").value("Весенняя экономика"));
        clock.set("2027-02-16T09:00:00Z");   // the next week alternates
        schedule("У-251").andExpect(jsonPath("$.currentWeekType").value("NUMERATOR"));

        // 8. nothing of the autumn semester was deleted
        assertThat(entries.count()).isEqualTo(4L);
    }

    @Test
    void aFileForgottenOnTheOldSemesterTurnsTheImportsHealthDownTheDayTheNewOneStarts() throws Exception {
        postJson("/api/v1/admin/periods", periodJson("SPRING", 2026, "2027-02-08", "2027-06-30", "2027-02-08", "DENOMINATOR"))
                .andExpect(status().isCreated());
        Long springId = jdbc.queryForObject("select id from academic_period where term = 'SPRING'", Long.class);
        clock.set("2027-02-09T09:00:00Z");   // spring has started; both files are imported fresh, eiu is still pinned to autumn
        importService.importFile(uits.getId(), file("весеннего семестра 2026-2027 учебного года", "У-251", "Весенняя физика"));
        importService.importFile(eiu.getId(), file("осеннего семестра 2025-2026 учебного года", "ЭБ-61", "Осенняя экономика"));

        mvc.perform(get("/actuator/health/imports")).andExpect(status().isServiceUnavailable());

        mvc.perform(admin(put("/api/v1/admin/sources/{id}/period", eiu.getId())).contentType(APPLICATION_JSON)
                .content("{\"periodId\":" + springId + "}")).andExpect(status().isOk());
        importService.importFile(eiu.getId(), file("осеннего семестра 2025-2026 учебного года", "ЭБ-61", "Осенняя экономика"));
        mvc.perform(get("/actuator/health/imports")).andExpect(status().isOk());
    }

    @Test
    void theGapBetweenSemestersKeepsShowingTheSemesterThatEndedLast() throws Exception {
        postJson("/api/v1/admin/periods", periodJson("SPRING", 2026, "2027-02-08", "2027-06-30", "2027-02-08", "DENOMINATOR"))
                .andExpect(status().isCreated());

        clock.set("2027-02-03T09:00:00Z");   // after the end of the session, before the spring semester starts

        schedule("У-251").andExpect(jsonPath("$.period.term").value("AUTUMN"));
    }
}
