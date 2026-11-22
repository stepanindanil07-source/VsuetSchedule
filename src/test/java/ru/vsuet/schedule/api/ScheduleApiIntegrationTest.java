package ru.vsuet.schedule.api;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import ru.vsuet.schedule.TestcontainersConfiguration;
import ru.vsuet.schedule.academic.domain.AcademicPeriod;
import ru.vsuet.schedule.academic.domain.AcademicTerm;
import ru.vsuet.schedule.academic.domain.OrganizationUnit;
import ru.vsuet.schedule.academic.domain.StudyGroup;
import ru.vsuet.schedule.academic.infrastructure.AcademicPeriodRepository;
import ru.vsuet.schedule.academic.infrastructure.OrganizationUnitRepository;
import ru.vsuet.schedule.academic.infrastructure.StudyGroupRepository;
import ru.vsuet.schedule.common.WeekType;
import ru.vsuet.schedule.importer.domain.ImportRun;
import ru.vsuet.schedule.importer.domain.ScheduleSource;
import ru.vsuet.schedule.importer.infrastructure.ImportRunRepository;
import ru.vsuet.schedule.importer.infrastructure.ScheduleSourceRepository;
import ru.vsuet.schedule.schedule.domain.LessonType;
import ru.vsuet.schedule.schedule.domain.ScheduleEntry;
import ru.vsuet.schedule.schedule.infrastructure.ScheduleEntryRepository;

/** The REST contract with the mobile client, exercised over MockMvc against a real database. */
@SpringBootTest(properties = "vsuet.import.scheduler.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ScheduleApiIntegrationTest.FixedClock.class})
class ScheduleApiIntegrationTest {

    /** "Today" is Wednesday 9 Sep 2026, which is in week 2 of the semester below: a denominator week. */
    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-09-09T09:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationUnitRepository units;
    @Autowired AcademicPeriodRepository periods;
    @Autowired StudyGroupRepository groups;
    @Autowired ScheduleSourceRepository sources;
    @Autowired ImportRunRepository runs;
    @Autowired ScheduleEntryRepository entries;

    private OrganizationUnit uits;
    private OrganizationUnit itf;
    private StudyGroup group;
    private StudyGroup inactiveGroup;

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE schedule_entry, import_run, study_group, schedule_source, academic_period, "
                + "organization_unit RESTART IDENTITY CASCADE");
        uits = units.save(new OrganizationUnit("uits", "Факультет управления и информатики в технологических системах"));
        itf = units.save(new OrganizationUnit("itf", "Инженерно-технический факультет"));
        AcademicPeriod spring = periods.save(new AcademicPeriod(2025, AcademicTerm.SPRING, LocalDate.of(2026, 2, 9),
                LocalDate.of(2026, 6, 30), LocalDate.of(2026, 2, 9), WeekType.NUMERATOR));
        AcademicPeriod autumn = periods.save(new AcademicPeriod(2026, AcademicTerm.AUTUMN, LocalDate.of(2026, 9, 1),
                LocalDate.of(2027, 1, 31), LocalDate.of(2026, 8, 31), WeekType.NUMERATOR));
        ScheduleSource source = sources.save(new ScheduleSource(uits.getId(), "test", "http://example.test/uits.xlsx"));
        ImportRun run = runs.save(ImportRun.start(source.getId(), null, Instant.parse("2026-09-01T00:00:00Z")));

        group = groups.save(new StudyGroup(uits.getId(), "У-251", source.getId()));
        group.contentChanged("h1", Instant.parse("2026-09-01T00:00:00Z"));
        group.contentChanged("h2", Instant.parse("2026-09-02T00:00:00Z"));
        group.contentChanged("h3", Instant.parse("2026-09-03T08:30:00Z"));
        group = groups.save(group);
        inactiveGroup = groups.save(new StudyGroup(uits.getId(), "У-999", source.getId()));
        inactiveGroup.deactivate();
        inactiveGroup = groups.save(inactiveGroup);
        groups.save(new StudyGroup(itf.getId(), "М-261", source.getId()));

        long g = group.getId();
        long a = autumn.getId();
        long r = run.getId();
        // deliberately saved out of order: the API must sort by day and time
        entries.save(lesson(g, a, r, DayOfWeek.TUESDAY, 8, 0, WeekType.EVERY_WEEK, null, "Математика", LessonType.LECTURE));
        entries.save(lesson(g, a, r, DayOfWeek.MONDAY, 9, 45, WeekType.EVERY_WEEK, 2, "Физика", LessonType.LAB));
        entries.save(lesson(g, a, r, DayOfWeek.MONDAY, 8, 0, WeekType.DENOMINATOR, null, "Химия", LessonType.PRACTICE));
        entries.save(lesson(g, a, r, DayOfWeek.MONDAY, 8, 0, WeekType.NUMERATOR, null, "История", LessonType.LECTURE));
        entries.save(lesson(g, a, r, DayOfWeek.MONDAY, 9, 45, WeekType.EVERY_WEEK, 1, "Физика", LessonType.LAB));
        // a lesson of the previous semester must never leak into the current schedule
        entries.save(lesson(g, spring.getId(), r, DayOfWeek.FRIDAY, 8, 0, WeekType.EVERY_WEEK, null, "Старый предмет", LessonType.LECTURE));
    }

    private static ScheduleEntry lesson(long group, long period, long run, DayOfWeek day, int hour, int minute,
                                        WeekType week, Integer subgroup, String subject, LessonType type) {
        return new ScheduleEntry(group, period, run, day, LocalTime.of(hour, minute), LocalTime.of(hour, minute).plusMinutes(95),
                week, subgroup, subject, type, "Иванов И.И.", "12", "raw", "1 курс", "A1");
    }

    // ---- catalog -----------------------------------------------------------------------------------------

    @Test
    void listsFacultiesSortedByName() throws Exception {
        mvc.perform(get("/api/v1/faculties"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].code").value("itf"))       // "Инженерно-..." sorts before "Факультет ..."
                .andExpect(jsonPath("$[1].code").value("uits"))
                .andExpect(jsonPath("$[1].id").value(uits.getId()))
                .andExpect(jsonPath("$[1].name").value("Факультет управления и информатики в технологических системах"));
    }

    @Test
    void listsOnlyActiveGroupsOfAFaculty() throws Exception {
        mvc.perform(get("/api/v1/faculties/{id}/groups", uits.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].code").value("У-251"))
                .andExpect(jsonPath("$[0].scheduleVersion").value(3))
                .andExpect(jsonPath("$[0].scheduleUpdatedAt").value("2026-09-03T08:30:00Z"));
    }

    @Test
    void unknownFacultyIsAProblemDetail404() throws Exception {
        mvc.perform(get("/api/v1/faculties/9999/groups"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not found"))
                .andExpect(jsonPath("$.detail").value("Faculty 9999 not found"));
    }

    // ---- schedule ----------------------------------------------------------------------------------------

    @Test
    void returnsTheCurrentSemestersScheduleSortedWithPeriodAndWeekInfo() throws Exception {
        mvc.perform(get("/api/v1/groups/{id}/schedule", group.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value(group.getId()))
                .andExpect(jsonPath("$.groupCode").value("У-251"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.today").value("2026-09-09"))
                .andExpect(jsonPath("$.currentWeekType").value("DENOMINATOR"))
                .andExpect(jsonPath("$.period.term").value("AUTUMN"))
                .andExpect(jsonPath("$.period.yearFrom").value(2026))
                .andExpect(jsonPath("$.period.firstWeekStart").value("2026-08-31"))
                .andExpect(jsonPath("$.period.firstWeekType").value("NUMERATOR"))
                .andExpect(jsonPath("$.lessons", hasSize(5)))
                .andExpect(jsonPath("$.lessons[*].subject")
                        .value(contains("История", "Химия", "Физика", "Физика", "Математика")))
                .andExpect(jsonPath("$.lessons[0].dayOfWeek").value("MONDAY"))
                .andExpect(jsonPath("$.lessons[0].startTime").value("08:00"))
                .andExpect(jsonPath("$.lessons[0].endTime").value("09:35"))
                .andExpect(jsonPath("$.lessons[0].weekType").value("NUMERATOR"))
                .andExpect(jsonPath("$.lessons[0].subgroup").value(nullValue()))
                .andExpect(jsonPath("$.lessons[0].lessonType").value("LECTURE"))
                .andExpect(jsonPath("$.lessons[0].teacher").value("Иванов И.И."))
                .andExpect(jsonPath("$.lessons[0].room").value("12"))
                .andExpect(jsonPath("$.lessons[2].subgroup").value(1))
                .andExpect(jsonPath("$.lessons[3].subgroup").value(2));
    }

    @Test
    void doesNotExposeInternalFields() throws Exception {
        mvc.perform(get("/api/v1/groups/{id}/schedule", group.getId()))
                .andExpect(jsonPath("$.lessons[0].rawText").doesNotExist())
                .andExpect(jsonPath("$.lessons[0].sourceRange").doesNotExist())
                .andExpect(jsonPath("$.lessons[0].id").doesNotExist());
    }

    @Test
    void weekTypeFilterKeepsThatWeeksLessonsAndEveryWeekLessons() throws Exception {
        mvc.perform(get("/api/v1/groups/{id}/schedule", group.getId()).param("weekType", "NUMERATOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lessons[*].subject").value(contains("История", "Физика", "Физика", "Математика")));
        mvc.perform(get("/api/v1/groups/{id}/schedule", group.getId()).param("weekType", "DENOMINATOR"))
                .andExpect(jsonPath("$.lessons[*].subject").value(contains("Химия", "Физика", "Физика", "Математика")));
    }

    @Test
    void subgroupFilterKeepsThatSubgroupAndWholeGroupLessons() throws Exception {
        mvc.perform(get("/api/v1/groups/{id}/schedule", group.getId()).param("subgroup", "2"))
                .andExpect(jsonPath("$.lessons", hasSize(4)))
                .andExpect(jsonPath("$.lessons[?(@.subgroup == 1)]").isEmpty())
                .andExpect(jsonPath("$.lessons[?(@.subgroup == 2)]").isNotEmpty());
    }

    @Test
    void filtersCombine() throws Exception {
        mvc.perform(get("/api/v1/groups/{id}/schedule", group.getId())
                        .param("weekType", "DENOMINATOR").param("subgroup", "1"))
                .andExpect(jsonPath("$.lessons", hasSize(3)))
                .andExpect(jsonPath("$.lessons[*].subject").value(contains("Химия", "Физика", "Математика")));
    }

    @Test
    void badFilterValuesAre400ProblemDetails() throws Exception {
        mvc.perform(get("/api/v1/groups/{id}/schedule", group.getId()).param("weekType", "SOMETIMES"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", "application/problem+json"));
        mvc.perform(get("/api/v1/groups/{id}/schedule", group.getId()).param("subgroup", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", "application/problem+json"));
        mvc.perform(get("/api/v1/groups/{id}/schedule", group.getId()).param("subgroup", "abc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownOrHiddenGroupIs404() throws Exception {
        mvc.perform(get("/api/v1/groups/9999/schedule")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/groups/{id}/schedule", inactiveGroup.getId())).andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", "application/problem+json"));
        mvc.perform(get("/api/v1/groups/{id}/schedule/version", inactiveGroup.getId())).andExpect(status().isNotFound());
    }

    @Test
    void withoutAnyRegisteredPeriodTheScheduleIsEmptyButTheGroupIsStillThere() throws Exception {
        jdbc.execute("DELETE FROM schedule_entry");
        jdbc.execute("DELETE FROM academic_period");

        mvc.perform(get("/api/v1/groups/{id}/schedule", group.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value(nullValue()))
                .andExpect(jsonPath("$.currentWeekType").value(nullValue()))
                .andExpect(jsonPath("$.lessons", hasSize(0)));
    }

    // ---- version -----------------------------------------------------------------------------------------

    @Test
    void versionEndpointReturnsVersionAndETag() throws Exception {
        mvc.perform(get("/api/v1/groups/{id}/schedule/version", group.getId()))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(jsonPath("$.groupId").value(group.getId()))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.updatedAt").value("2026-09-03T08:30:00Z"));
    }

    @Test
    void matchingETagGets304AndAStaleOneGetsTheBody() throws Exception {
        mvc.perform(get("/api/v1/groups/{id}/schedule/version", group.getId()).header("If-None-Match", "\"3\""))
                .andExpect(status().isNotModified());
        mvc.perform(get("/api/v1/groups/{id}/schedule/version", group.getId()).header("If-None-Match", "\"2\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
    }

    // ---- OpenAPI -----------------------------------------------------------------------------------------

    @Test
    void openApiDocumentDescribesAllFourEndpoints() throws Exception {
        mvc.perform(get("/v3/api-docs").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("VSUET Schedule API"))
                .andExpect(jsonPath("$.paths['/api/v1/faculties']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/faculties/{facultyId}/groups']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/groups/{groupId}/schedule']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/groups/{groupId}/schedule/version']").exists());
    }
}
