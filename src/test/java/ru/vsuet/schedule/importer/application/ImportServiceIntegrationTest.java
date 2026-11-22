package ru.vsuet.schedule.importer.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
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
import ru.vsuet.schedule.importer.domain.ImportStatus;
import ru.vsuet.schedule.importer.domain.ScheduleSource;
import ru.vsuet.schedule.importer.infrastructure.ImportRunRepository;
import ru.vsuet.schedule.importer.infrastructure.ScheduleSourceRepository;
import ru.vsuet.schedule.importer.parser.fuits.FuitsExcelParser;
import ru.vsuet.schedule.importer.parser.fuits.WorkbookFixture;
import ru.vsuet.schedule.importer.parser.spo.SpoExcelParser;
import ru.vsuet.schedule.schedule.domain.ScheduleEntry;
import ru.vsuet.schedule.schedule.infrastructure.ScheduleEntryRepository;

/** Real PostgreSQL (Testcontainers), real parser, real transactions. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ImportServiceIntegrationTest {

    private static final String AUTUMN_2026 = "осеннего семестра 2026-2027 учебного года";
    private static final String AUTUMN_2025 = "осеннего семестра 2025-2026 учебного года";
    private static final String PHYSICS = "пр.Физика\nИванов И.И.   12";
    private static final String CHEMISTRY = "л.Химия\nПетров П.П.   13";

    @Autowired ImportService importService;
    @Autowired OrganizationUnitRepository units;
    @Autowired AcademicPeriodRepository periods;
    @Autowired StudyGroupRepository groups;
    @Autowired ScheduleSourceRepository sources;
    @Autowired ScheduleEntryRepository entries;
    @Autowired ImportRunRepository runs;
    @Autowired JdbcTemplate jdbc;

    private OrganizationUnit unit;
    private AcademicPeriod autumn2026;
    private ScheduleSource source;

    @BeforeEach
    void cleanDatabaseAndSeed() {
        jdbc.execute("TRUNCATE schedule_entry, import_run, study_group, schedule_source, academic_period, "
                + "organization_unit RESTART IDENTITY CASCADE");
        unit = units.save(new OrganizationUnit("uits", "ФУИТС"));
        autumn2026 = periods.save(new AcademicPeriod(2026, AcademicTerm.AUTUMN, LocalDate.of(2026, 9, 1),
                LocalDate.of(2027, 1, 31), LocalDate.of(2026, 8, 31), WeekType.NUMERATOR));
        source = sources.save(new ScheduleSource(unit.getId(), FuitsExcelParser.KEY, "https://example.test/uits.xlsx"));
    }

    // ---- fixtures ----------------------------------------------------------------------------------------

    /** Groups Г-1 (column C) and Г-2 (column D), Monday, two slots. */
    private static WorkbookFixture twoGroups(String semester) {
        return WorkbookFixture.xlsx().sheet("1 курс", semester, "Г-1", 1, "Г-2", 1)
                .day("ПОНЕДЕЛЬНИК").slot("08.00-09.35", 2).slot("09.45-11.20", 2);
    }

    private static byte[] bytes(WorkbookFixture fixture) {
        try (InputStream in = fixture.stream()) {
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Four lessons: two per group. */
    private static byte[] fileA() {
        return bytes(twoGroups(AUTUMN_2026).put("C3", PHYSICS).put("C5", CHEMISTRY).put("D3", CHEMISTRY).put("D5", PHYSICS));
    }

    /** Same as A, but only Г-2's first lesson has a different teacher. */
    private static byte[] fileAWithGroup2Changed() {
        return bytes(twoGroups(AUTUMN_2026).put("C3", PHYSICS).put("C5", CHEMISTRY)
                .put("D3", "л.Химия\nСидоров С.С.   13").put("D5", PHYSICS));
    }

    private Map<String, StudyGroup> groupsByCode() {
        return groups.findByUnitId(unit.getId()).stream().collect(Collectors.toMap(StudyGroup::getCode, g -> g));
    }

    private Set<Long> entryIds(StudyGroup group) {
        return entries.findByGroupIdAndPeriodId(group.getId(), autumn2026.getId()).stream()
                .map(ScheduleEntry::getId).collect(Collectors.toSet());
    }

    // ---- happy path --------------------------------------------------------------------------------------

    @Test
    void firstImportCreatesGroupsEntriesAndHistory() {
        ImportResult result = importService.importFile(source.getId(), fileA());

        assertThat(result.status()).isEqualTo(ImportStatus.SUCCESS);
        assertThat(result.groupsCreated()).isEqualTo(2);
        assertThat(result.entriesWritten()).isEqualTo(4);
        assertThat(groupsByCode()).containsOnlyKeys("Г-1", "Г-2");
        assertThat(groupsByCode().values()).allSatisfy(g -> {
            assertThat(g.getScheduleVersion()).isEqualTo(1);
            assertThat(g.isActive()).isTrue();
            assertThat(g.getScheduleUpdatedAt()).isNotNull();
            assertThat(g.getCourse()).as("course from sheet '1 курс'").isEqualTo(1);
        });
        assertThat(entries.count()).isEqualTo(4);
        ScheduleEntry first = entries.findByGroupIdAndPeriodId(groupsByCode().get("Г-1").getId(), autumn2026.getId())
                .stream().filter(e -> e.getSourceRange().equals("C3")).findFirst().orElseThrow();
        assertThat(first.getSubject()).isEqualTo("Физика");
        assertThat(first.getWeekType()).isEqualTo(WeekType.NUMERATOR);
        assertThat(first.getDayOfWeek()).isEqualTo(java.time.DayOfWeek.MONDAY);
        assertThat(first.getRawText()).isEqualTo(PHYSICS);

        ImportRun run = runs.findById(result.runId()).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(ImportStatus.SUCCESS);
        assertThat(run.getRecognized()).isEqualTo(4);
        assertThat(run.getPeriodId()).isEqualTo(autumn2026.getId());
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(sources.findById(source.getId()).orElseThrow().getLastSha256()).isEqualTo(run.getSha256());
    }

    @Test
    void sameFileAgainIsSkippedAndChangesNothing() {
        importService.importFile(source.getId(), fileA());
        Set<Long> idsBefore = entries.findAll().stream().map(ScheduleEntry::getId).collect(Collectors.toSet());

        ImportResult second = importService.importFile(source.getId(), fileA());

        assertThat(second.status()).isEqualTo(ImportStatus.SKIPPED_UNCHANGED);
        assertThat(entries.findAll()).extracting(ScheduleEntry::getId).containsExactlyInAnyOrderElementsOf(idsBefore);
        assertThat(groupsByCode().values()).allSatisfy(g -> assertThat(g.getScheduleVersion()).isEqualTo(1));
        assertThat(runs.findAll()).extracting(ImportRun::getStatus)
                .containsExactlyInAnyOrder(ImportStatus.SUCCESS, ImportStatus.SKIPPED_UNCHANGED);
    }

    @Test
    void onlyTheGroupThatChangedGetsANewVersionAndNewRows() {
        importService.importFile(source.getId(), fileA());
        Set<Long> group1Before = entryIds(groupsByCode().get("Г-1"));
        Set<Long> group2Before = entryIds(groupsByCode().get("Г-2"));

        ImportResult result = importService.importFile(source.getId(), fileAWithGroup2Changed());

        assertThat(result.status()).isEqualTo(ImportStatus.SUCCESS);
        assertThat(result.groupsUpdated()).isEqualTo(1);
        assertThat(result.groupsUnchanged()).isEqualTo(1);
        Map<String, StudyGroup> after = groupsByCode();
        assertThat(after.get("Г-1").getScheduleVersion()).isEqualTo(1);
        assertThat(entryIds(after.get("Г-1"))).isEqualTo(group1Before);
        assertThat(after.get("Г-2").getScheduleVersion()).isEqualTo(2);
        assertThat(entryIds(after.get("Г-2"))).hasSize(2).doesNotContainAnyElementsOf(group2Before);
        assertThat(entries.count()).isEqualTo(4);
        assertThat(entries.findByGroupIdAndPeriodId(after.get("Г-2").getId(), autumn2026.getId()))
                .extracting(ScheduleEntry::getTeacherText).contains("Сидоров С.С.");
    }

    @Test
    void groupMissingFromTheFileIsHiddenNotDeleted() {
        importService.importFile(source.getId(), fileA());
        byte[] onlyGroup1 = bytes(WorkbookFixture.xlsx().sheet("1 курс", AUTUMN_2026, "Г-1", 1)
                .day("ПОНЕДЕЛЬНИК").slot("08.00-09.35", 2).slot("09.45-11.20", 2).put("C3", PHYSICS).put("C5", CHEMISTRY)
                .put("C4", CHEMISTRY));

        ImportResult result = importService.importFile(source.getId(), onlyGroup1);

        assertThat(result.status()).isEqualTo(ImportStatus.SUCCESS);
        assertThat(result.groupsDeactivated()).isEqualTo(1);
        Map<String, StudyGroup> after = groupsByCode();
        assertThat(after.get("Г-2").isActive()).isFalse();
        assertThat(entryIds(after.get("Г-2"))).hasSize(2);
        assertThat(after.get("Г-1").isActive()).isTrue();
    }

    @Test
    void groupsOwnedByAnotherSourceOfTheSameUnitAreNotHidden() {
        ScheduleSource second = sources.save(new ScheduleSource(unit.getId(), FuitsExcelParser.KEY, "https://example.test/second.xlsx"));
        importService.importFile(source.getId(), fileA());
        byte[] otherFile = bytes(WorkbookFixture.xlsx().sheet("2 курс", AUTUMN_2026, "Г-3", 1)
                .day("ПОНЕДЕЛЬНИК").slot("08.00-09.35", 2).put("C3", PHYSICS));

        ImportResult result = importService.importFile(second.getId(), otherFile);

        assertThat(result.status()).isEqualTo(ImportStatus.SUCCESS);
        assertThat(result.groupsDeactivated()).isZero();
        assertThat(groupsByCode()).containsOnlyKeys("Г-1", "Г-2", "Г-3");
        assertThat(groupsByCode().values()).allSatisfy(g -> assertThat(g.isActive()).isTrue());
    }

    @Test
    void courseChangeAloneUpdatesTheGroupButNotItsScheduleVersion() {
        importService.importFile(source.getId(), fileA());
        byte[] sameLessonsOnSecondCourseSheet = bytes(WorkbookFixture.xlsx().sheet("2 курс", AUTUMN_2026, "Г-1", 1, "Г-2", 1)
                .day("ПОНЕДЕЛЬНИК").slot("08.00-09.35", 2).slot("09.45-11.20", 2)
                .put("C3", PHYSICS).put("C5", CHEMISTRY).put("D3", CHEMISTRY).put("D5", PHYSICS));

        ImportResult result = importService.importFile(source.getId(), sameLessonsOnSecondCourseSheet);

        assertThat(result.status()).isEqualTo(ImportStatus.SUCCESS);
        assertThat(result.entriesWritten()).isZero();
        assertThat(groupsByCode().values()).allSatisfy(g -> {
            assertThat(g.getCourse()).isEqualTo(2);
            assertThat(g.getScheduleVersion()).as("lessons did not change").isEqualTo(1);
        });
    }

    @Test
    void realUitsFileImportsCompletelyAndIsThenSkipped() throws Exception {
        periods.save(new AcademicPeriod(2025, AcademicTerm.SPRING, LocalDate.of(2026, 2, 9),
                LocalDate.of(2026, 6, 30), LocalDate.of(2026, 2, 9), WeekType.NUMERATOR));
        byte[] real;
        try (InputStream in = getClass().getResourceAsStream("/fuits/uits.xlsx")) {
            real = in.readAllBytes();
        }

        ImportResult first = importService.importFile(source.getId(), real);
        ImportResult second = importService.importFile(source.getId(), real);

        assertThat(first.status()).isEqualTo(ImportStatus.SUCCESS);
        assertThat(first.groupsCreated()).isEqualTo(42);
        assertThat(entries.count()).isEqualTo(1161);
        assertThat(second.status()).isEqualTo(ImportStatus.SKIPPED_UNCHANGED);
        assertThat(entries.count()).isEqualTo(1161);
    }

    // ---- failures must not damage existing data ---------------------------------------------------------

    private void assertFileAStillIntact() {
        assertThat(entries.count()).isEqualTo(4);
        assertThat(groupsByCode().values()).allSatisfy(g -> {
            assertThat(g.getScheduleVersion()).isEqualTo(1);
            assertThat(g.isActive()).isTrue();
        });
        assertThat(entries.findAll()).extracting(ScheduleEntry::getTeacherText)
                .containsExactlyInAnyOrder("Иванов И.И.", "Петров П.П.", "Петров П.П.", "Иванов И.И.");
    }

    @Test
    void unreadableFileFailsAndKeepsTheCurrentSchedule() {
        importService.importFile(source.getId(), fileA());
        String shaBefore = sources.findById(source.getId()).orElseThrow().getLastSha256();

        ImportResult result = importService.importFile(source.getId(), "this is not excel".getBytes(StandardCharsets.UTF_8));

        assertThat(result.status()).isEqualTo(ImportStatus.FAILED);
        assertFileAStillIntact();
        assertThat(sources.findById(source.getId()).orElseThrow().getLastSha256()).isEqualTo(shaBefore);
        ImportRun failed = runs.findById(result.runId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(ImportStatus.FAILED);
        assertThat(failed.getErrorMessage()).contains("Cannot read");
    }

    @Test
    void structurallyBrokenFileFailsAndKeepsTheCurrentSchedule() {
        importService.importFile(source.getId(), fileA());
        byte[] noLessons = bytes(twoGroups(AUTUMN_2026));

        ImportResult result = importService.importFile(source.getId(), noLessons);

        assertThat(result.status()).isEqualTo(ImportStatus.FAILED);
        assertThat(result.message()).contains("No lessons");
        assertFileAStillIntact();
    }

    @Test
    void fileWithMostLessonsMissingIsRejectedAsSuspicious() {
        importService.importFile(source.getId(), fileA());
        byte[] almostEmpty = bytes(twoGroups(AUTUMN_2026).put("C3", PHYSICS));

        ImportResult result = importService.importFile(source.getId(), almostEmpty);

        assertThat(result.status()).isEqualTo(ImportStatus.FAILED);
        assertThat(result.message()).contains("Rejected").contains("1 lessons");
        assertFileAStillIntact();
        assertThat(runs.findById(result.runId()).orElseThrow().getDiagnostics()).contains("SUSPICIOUS_DROP");
    }

    @Test
    void databaseErrorHalfwayRollsBackEverythingTheImportAlreadyWrote() {
        importService.importFile(source.getId(), fileA());
        // Г-1 changes legitimately and is written first; Г-2's subject is too long for the column (512)
        byte[] poisoned = bytes(twoGroups(AUTUMN_2026)
                .put("C3", "пр.Математика\nИванов И.И.   12").put("C5", CHEMISTRY)
                .put("D3", "пр." + "я".repeat(600) + "\nПетров П.П.   13").put("D5", PHYSICS));

        ImportResult result = importService.importFile(source.getId(), poisoned);

        assertThat(result.status()).isEqualTo(ImportStatus.FAILED);
        assertFileAStillIntact();
        assertThat(entries.findAll()).extracting(ScheduleEntry::getSubject).doesNotContain("Математика");
    }

    @Test
    void periodThatIsNotRegisteredFailsWithoutCreatingAnything() {
        byte[] wrongSemester = bytes(twoGroups(AUTUMN_2025).put("C3", PHYSICS).put("D3", CHEMISTRY));

        ImportResult result = importService.importFile(source.getId(), wrongSemester);

        assertThat(result.status()).isEqualTo(ImportStatus.FAILED);
        assertThat(result.message()).contains("not registered");
        assertThat(groups.count()).isZero();
        assertThat(entries.count()).isZero();
        assertThat(runs.findById(result.runId()).orElseThrow().getDiagnostics()).contains("PERIOD_NOT_REGISTERED");
    }

    // ---- files that do not state their semester (SPO 2-3-4_kurs.xls) ----------------------------------

    private static byte[] spoFileWithoutSemester() {
        return bytes(WorkbookFixture.xls().sheetSpo("Лист3", null, "А-1", 2, "Б-1", 2)
                .day("ПОНЕДЕЛЬНИК").slot("1) 8 00", 2).slot("2) 9 45", 2)
                .put("C5", PHYSICS).put("E5", CHEMISTRY));
    }

    @Test
    void fileWithoutSemesterNeedsAPinnedSourceOtherwiseNothingIsWritten() {
        ScheduleSource spo = sources.save(new ScheduleSource(unit.getId(), SpoExcelParser.KEY, "https://example.test/spo.xls"));

        ImportResult result = importService.importFile(spo.getId(), spoFileWithoutSemester());

        assertThat(result.status()).isEqualTo(ImportStatus.FAILED);
        assertThat(result.message()).contains("does not state its semester").contains("pin");
        assertThat(groups.count()).isZero();
        assertThat(entries.count()).isZero();
        assertThat(runs.findById(result.runId()).orElseThrow().getDiagnostics()).contains("PERIOD_UNKNOWN");
    }

    @Test
    void pinnedSourceImportsAFileWithoutSemesterIntoItsPeriod() {
        ScheduleSource spo = new ScheduleSource(unit.getId(), SpoExcelParser.KEY, "https://example.test/spo.xls");
        spo.pinToPeriod(autumn2026.getId());
        spo = sources.save(spo);

        ImportResult result = importService.importFile(spo.getId(), spoFileWithoutSemester());

        assertThat(result.status()).isEqualTo(ImportStatus.SUCCESS);
        assertThat(entries.findAll()).hasSize(2).allSatisfy(e -> assertThat(e.getPeriodId()).isEqualTo(autumn2026.getId()));
        ImportRun run = runs.findById(result.runId()).orElseThrow();
        assertThat(run.getPeriodId()).isEqualTo(autumn2026.getId());
        assertThat(run.getDiagnostics()).doesNotContain("PERIOD_PINNED_MISMATCH");
        assertThat(groupsByCode()).containsOnlyKeys("А-1", "Б-1");
    }

    // ---- pinned period -----------------------------------------------------------------------------------

    @Test
    void sourcePinnedToAPeriodIgnoresTheSemesterTextInTheFileButWarns() {
        source.pinToPeriod(autumn2026.getId());
        source = sources.save(source);
        // the file claims 2025-2026 (a typo, like eiu.xlsx) but the schedule is for autumn 2026-2027
        byte[] typo = bytes(twoGroups(AUTUMN_2025).put("C3", PHYSICS).put("D3", CHEMISTRY));

        ImportResult result = importService.importFile(source.getId(), typo);

        assertThat(result.status()).isEqualTo(ImportStatus.SUCCESS);
        assertThat(entries.findAll()).hasSize(2).allSatisfy(e -> assertThat(e.getPeriodId()).isEqualTo(autumn2026.getId()));
        ImportRun run = runs.findById(result.runId()).orElseThrow();
        assertThat(run.getPeriodId()).isEqualTo(autumn2026.getId());
        assertThat(run.getDiagnostics()).contains("PERIOD_PINNED_MISMATCH");
    }
}
