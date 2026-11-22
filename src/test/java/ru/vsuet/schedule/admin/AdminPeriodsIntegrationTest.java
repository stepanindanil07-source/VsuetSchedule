package ru.vsuet.schedule.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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

/** Registering and correcting semesters: the rules that keep the week parity from being shifted by a typo. */
@SpringBootTest(properties = {"vsuet.admin.token=test-token", "vsuet.import.scheduler.enabled=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminPeriodsIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationUnitRepository units;
    @Autowired AcademicPeriodRepository periods;
    @Autowired StudyGroupRepository groups;
    @Autowired ScheduleSourceRepository sources;
    @Autowired ImportRunRepository runs;
    @Autowired ScheduleEntryRepository entries;

    private AcademicPeriod autumn2026;

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE schedule_entry, import_run, study_group, schedule_source, academic_period, "
                + "organization_unit RESTART IDENTITY CASCADE");
        autumn2026 = periods.save(new AcademicPeriod(2026, AcademicTerm.AUTUMN, LocalDate.of(2026, 9, 1),
                LocalDate.of(2027, 1, 31), LocalDate.of(2026, 8, 31), WeekType.NUMERATOR));
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer test-token");
    }

    private ResultActions createPeriod(String json) throws Exception {
        return mvc.perform(auth(post("/api/v1/admin/periods")).contentType(APPLICATION_JSON).content(json));
    }

    private static String periodJson(String term, int yearFrom, String start, String end, String firstWeek, String type) {
        return """
                {"term":"%s","yearFrom":%d,"startDate":"%s","endDate":"%s","firstWeekStart":"%s","firstWeekType":"%s"}"""
                .formatted(term, yearFrom, start, end, firstWeek, type);
    }

    // ---- create ------------------------------------------------------------------------------------------

    @Test
    void registersANewSemester() throws Exception {
        // 30 Aug 2027 is a Monday, 1 Sep 2027 a Wednesday of the same week
        createPeriod(periodJson("AUTUMN", 2027, "2027-09-01", "2028-01-31", "2027-08-30", "DENOMINATOR"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/admin/periods/")))
                .andExpect(jsonPath("$.term").value("AUTUMN"))
                .andExpect(jsonPath("$.yearFrom").value(2027))
                .andExpect(jsonPath("$.yearTo").value(2028))
                .andExpect(jsonPath("$.firstWeekStart").value("2027-08-30"))
                .andExpect(jsonPath("$.firstWeekType").value("DENOMINATOR"));

        mvc.perform(auth(get("/api/v1/admin/periods")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].yearFrom").value(2027))      // newest first
                .andExpect(jsonPath("$[1].yearFrom").value(2026));
    }

    @Test
    void aSpringSemesterStartsInTheSecondYearOfTheAcademicYear() throws Exception {
        createPeriod(periodJson("SPRING", 2026, "2027-02-08", "2027-06-30", "2027-02-08", "NUMERATOR"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.yearTo").value(2027));
    }

    @Test
    void duplicateTermAndYearIsAConflict() throws Exception {
        createPeriod(periodJson("AUTUMN", 2026, "2026-09-01", "2027-01-31", "2026-08-31", "NUMERATOR"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("already exists")));
    }

    @Test
    void datesOverlappingAnotherSemesterAreAConflict() throws Exception {
        // 25 Jan 2027 is a Monday; the autumn semester runs until 31 Jan
        createPeriod(periodJson("SPRING", 2026, "2027-01-25", "2027-06-30", "2027-01-25", "NUMERATOR"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("overlap")));
    }

    @Test
    void aFirstWeekThatIsNotAMondayIsRejected() throws Exception {
        createPeriod(periodJson("SPRING", 2026, "2027-02-08", "2027-06-30", "2027-02-09", "NUMERATOR"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value(containsString("must be a Monday")));
    }

    @Test
    void aFirstWeekThatDoesNotContainTheFirstDayOfTheSemesterIsRejected() throws Exception {
        // a Monday, but a week too early: every lesson of the semester would get the opposite parity
        createPeriod(periodJson("SPRING", 2026, "2027-02-08", "2027-06-30", "2027-02-01", "NUMERATOR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("must contain the first day")));
    }

    @Test
    void everyWeekIsNotAParityForTheFirstWeek() throws Exception {
        createPeriod(periodJson("SPRING", 2026, "2027-02-08", "2027-06-30", "2027-02-08", "EVERY_WEEK"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void startMustBeBeforeEnd() throws Exception {
        createPeriod(periodJson("SPRING", 2026, "2027-02-08", "2027-02-08", "2027-02-08", "NUMERATOR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("before endDate")));
    }

    @Test
    void theYearOfTheStartMustAgreeWithTermAndAcademicYear() throws Exception {
        // an AUTUMN 2027 semester cannot start in 2026
        createPeriod(periodJson("AUTUMN", 2027, "2026-09-01", "2027-01-31", "2026-08-31", "NUMERATOR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("must start in 2027")));
    }

    @Test
    void missingFieldsAndGarbageAreBadRequestsNotServerErrors() throws Exception {
        createPeriod("{}").andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        createPeriod("not json").andExpect(status().isBadRequest());
        createPeriod(periodJson("WINTER", 2027, "2027-09-01", "2028-01-31", "2027-08-30", "NUMERATOR"))
                .andExpect(status().isBadRequest());
    }

    // ---- update ------------------------------------------------------------------------------------------

    private StudyGroup groupWithLessonsIn(AcademicPeriod period, String code, long version) {
        OrganizationUnit unit = units.findAll().stream().findFirst().orElseGet(() -> units.save(new OrganizationUnit("u", "U")));
        ScheduleSource source = sources.findAll().stream().findFirst()
                .orElseGet(() -> sources.save(new ScheduleSource(unit.getId(), "k", "http://example.test/x.xlsx")));
        ImportRun run = runs.findAll().stream().findFirst()
                .orElseGet(() -> runs.save(ImportRun.start(source.getId(), null, java.time.Instant.parse("2026-09-01T00:00:00Z"))));
        StudyGroup group = new StudyGroup(unit.getId(), code, source.getId());
        for (long i = 0; i < version; i++) {
            group.contentChanged("h" + i, java.time.Instant.parse("2026-09-01T00:00:00Z"));
        }
        group = groups.save(group);
        entries.save(new ScheduleEntry(group.getId(), period.getId(), run.getId(), DayOfWeek.MONDAY, LocalTime.of(8, 0),
                LocalTime.of(9, 35), WeekType.EVERY_WEEK, null, "Физика", LessonType.LECTURE, "Т.Т.", "1", "raw", "S", "A1"));
        return group;
    }

    private long versionOf(StudyGroup g) {
        return groups.findById(g.getId()).orElseThrow().getScheduleVersion();
    }

    private ResultActions updatePeriod(long id, String start, String end, String firstWeek, String type) throws Exception {
        return mvc.perform(auth(put("/api/v1/admin/periods/{id}", id)).contentType(APPLICATION_JSON).content("""
                {"startDate":"%s","endDate":"%s","firstWeekStart":"%s","firstWeekType":"%s"}""".formatted(start, end, firstWeek, type)));
    }

    @Test
    void correctingTheAnchorRaisesTheVersionOfEveryGroupOfThatSemesterSoCachedAppsReloadIt() throws Exception {
        StudyGroup affected = groupWithLessonsIn(autumn2026, "У-251", 3);
        StudyGroup alsoAffected = groupWithLessonsIn(autumn2026, "У-252", 1);
        AcademicPeriod spring = periods.save(new AcademicPeriod(2025, AcademicTerm.SPRING, LocalDate.of(2026, 2, 9),
                LocalDate.of(2026, 6, 30), LocalDate.of(2026, 2, 9), WeekType.NUMERATOR));
        StudyGroup untouched = groupWithLessonsIn(spring, "У-111", 5);

        updatePeriod(autumn2026.getId(), "2026-09-01", "2027-01-31", "2026-08-31", "DENOMINATOR")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period.firstWeekType").value("DENOMINATOR"))
                .andExpect(jsonPath("$.groupsRefreshed").value(2));

        assertThat(versionOf(affected)).isEqualTo(4);
        assertThat(versionOf(alsoAffected)).isEqualTo(2);
        assertThat(versionOf(untouched)).as("another semester's groups are not touched").isEqualTo(5);
        assertThat(groups.findById(affected.getId()).orElseThrow().getScheduleUpdatedAt()).isNotNull();
        mvc.perform(get("/api/v1/groups/{id}/schedule/version", affected.getId()))
                .andExpect(jsonPath("$.version").value(4));
    }

    @Test
    void savingTheSameValuesAgainChangesNothing() throws Exception {
        StudyGroup group = groupWithLessonsIn(autumn2026, "У-251", 3);

        updatePeriod(autumn2026.getId(), "2026-09-01", "2027-01-31", "2026-08-31", "NUMERATOR")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupsRefreshed").value(0));

        assertThat(versionOf(group)).isEqualTo(3);
    }

    @Test
    void anUpdateIsValidatedLikeACreate() throws Exception {
        updatePeriod(autumn2026.getId(), "2026-09-01", "2027-01-31", "2026-09-01", "NUMERATOR")   // a Tuesday
                .andExpect(status().isBadRequest());
        updatePeriod(autumn2026.getId(), "2026-09-01", "2027-01-31", "2026-08-31", "EVERY_WEEK")
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUpdateCannotMakeTwoSemestersOverlap() throws Exception {
        periods.save(new AcademicPeriod(2026, AcademicTerm.SPRING, LocalDate.of(2027, 2, 8), LocalDate.of(2027, 6, 30),
                LocalDate.of(2027, 2, 8), WeekType.NUMERATOR));

        updatePeriod(autumn2026.getId(), "2026-09-01", "2027-02-10", "2026-08-31", "NUMERATOR")
                .andExpect(status().isConflict());
    }

    @Test
    void aSemesterMayBeSavedAgainWithItsOwnDatesWithoutBeingTreatedAsAnOverlap() throws Exception {
        updatePeriod(autumn2026.getId(), "2026-09-01", "2027-01-24", "2026-08-31", "NUMERATOR")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period.endDate").value("2027-01-24"));
    }

    @Test
    void unknownSemesterIsNotFound() throws Exception {
        updatePeriod(9999, "2026-09-01", "2027-01-31", "2026-08-31", "NUMERATOR").andExpect(status().isNotFound());
    }
}
