package ru.vsuet.schedule.importer.parser.fuits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.time.DayOfWeek;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import ru.vsuet.schedule.academic.domain.AcademicTerm;
import ru.vsuet.schedule.importer.parser.ParseIssue;
import ru.vsuet.schedule.importer.parser.ParsedEntry;
import ru.vsuet.schedule.importer.parser.ParsedSchedule;
import ru.vsuet.schedule.importer.parser.ScheduleParseException;
import ru.vsuet.schedule.common.WeekType;

/**
 * Geometry tests on small hand-built workbooks. In every fixture group "Г-1" occupies columns C:D
 * (left = subgroup 1, right = subgroup 2), "Г-2" column E, "Г-3" (when used) columns F:H.
 * First slot is rows 3 (numerator) and 4 (denominator), second slot rows 5 and 6.
 */
class FuitsExcelParserTest {

    private static final String SEMESTER = "весеннего семестра  2025-2026   учебного года";
    private static final String LESSON = "пр.Физика\nИванов И.И.   12";

    private final FuitsExcelParser parser = new FuitsExcelParser();

    private static WorkbookFixture standard() {
        return WorkbookFixture.xlsx()
                .sheet("1 курс", SEMESTER, "Г-1", 2, "Г-2", 1)
                .day("ПОНЕДЕЛЬНИК ").slot("08.00-09.35 ", 2).slot("09.45-11.20", 2);
    }

    private ParsedSchedule parse(WorkbookFixture fixture) {
        return parser.parse(fixture.stream());
    }

    private static ParsedEntry entryAt(ParsedSchedule s, String position) {
        return s.entries().stream().filter(e -> e.location().position().equals(position)).findFirst()
                .orElseThrow(() -> new AssertionError("no entry at " + position + " in " + s.entries()));
    }

    private static void assertScope(ParsedEntry e, WeekType week, Integer subgroup) {
        assertThat(e.weekType()).as("week type").isEqualTo(week);
        assertThat(e.subgroup()).as("subgroup").isEqualTo(subgroup);
    }

    // ---- week / subgroup geometry ------------------------------------------------------------------------

    @Test
    void mergedAcrossBothRowsAndAllColumnsIsEveryWeekForTheWholeGroup() {
        ParsedSchedule s = parse(standard().put("C3", LESSON).merge("C3:D4"));

        assertScope(entryAt(s, "C3:D4"), WeekType.EVERY_WEEK, null);
    }

    @Test
    void mergedAcrossAllColumnsOfOneRowIsOneWeekForTheWholeGroup() {
        ParsedSchedule s = parse(standard().put("C3", LESSON).merge("C3:D3").put("C4", LESSON).merge("C4:D4"));

        assertScope(entryAt(s, "C3:D3"), WeekType.NUMERATOR, null);
        assertScope(entryAt(s, "C4:D4"), WeekType.DENOMINATOR, null);
    }

    @Test
    void mergedAcrossBothRowsOfOneColumnIsEveryWeekForOneSubgroup() {
        ParsedSchedule s = parse(standard().put("C3", LESSON).merge("C3:C4").put("D3", LESSON).merge("D3:D4"));

        assertScope(entryAt(s, "C3:C4"), WeekType.EVERY_WEEK, 1);
        assertScope(entryAt(s, "D3:D4"), WeekType.EVERY_WEEK, 2);
    }

    @Test
    void singleCellIsOneWeekForOneSubgroup() {
        ParsedSchedule s = parse(standard().put("C3", LESSON).put("D4", LESSON).put("D5", LESSON).put("C6", LESSON));

        assertScope(entryAt(s, "C3"), WeekType.NUMERATOR, 1);
        assertScope(entryAt(s, "D4"), WeekType.DENOMINATOR, 2);
        assertScope(entryAt(s, "D5"), WeekType.NUMERATOR, 2);
        assertScope(entryAt(s, "C6"), WeekType.DENOMINATOR, 1);
    }

    @Test
    void groupWithOneColumnHasNoSubgroups() {
        ParsedSchedule s = parse(standard().put("E3", LESSON).merge("E3:E4").put("E5", LESSON).put("E6", LESSON));

        assertScope(entryAt(s, "E3:E4"), WeekType.EVERY_WEEK, null);
        assertScope(entryAt(s, "E5"), WeekType.NUMERATOR, null);
        assertScope(entryAt(s, "E6"), WeekType.DENOMINATOR, null);
    }

    @Test
    void groupWithThreeColumnsNumbersSubgroupsByColumn() {
        WorkbookFixture f = WorkbookFixture.xlsx()
                .sheet("1 курс", SEMESTER, "Г-3", 3)
                .day("СРЕДА").slot("08.00-09.35", 2)
                .put("C3", LESSON).put("D3", LESSON).put("E3", LESSON).put("C4", LESSON).merge("C4:E4");

        ParsedSchedule s = parse(f);

        assertScope(entryAt(s, "C3"), WeekType.NUMERATOR, 1);
        assertScope(entryAt(s, "D3"), WeekType.NUMERATOR, 2);
        assertScope(entryAt(s, "E3"), WeekType.NUMERATOR, 3);
        assertScope(entryAt(s, "C4:E4"), WeekType.DENOMINATOR, null);
    }

    @Test
    void lessonCoveringSomeButNotAllSubgroupColumnsIsSkippedWithAnError() {
        WorkbookFixture f = WorkbookFixture.xlsx()
                .sheet("1 курс", SEMESTER, "Г-3", 3)
                .day("СРЕДА").slot("08.00-09.35", 2)
                .put("C3", LESSON).merge("C3:D3").put("E4", LESSON);

        ParsedSchedule s = parse(f);

        assertThat(s.entries()).hasSize(1);
        assertThat(s.diagnostics().skipped()).isEqualTo(1);
        assertThat(s.diagnostics().issues()).singleElement().satisfies(i -> {
            assertThat(i.code()).isEqualTo("PARTIAL_GROUP_SPAN");
            assertThat(i.location().position()).isEqualTo("C3:D3");
            assertThat(i.rawText()).isEqualTo(LESSON);
        });
    }

    @Test
    void mergedRangeCrossingTheSlotBoundaryIsSkippedWithAnError() {
        ParsedSchedule s = parse(standard().put("C4", LESSON).merge("C4:C5").put("E3", LESSON));

        assertThat(s.entries()).extracting(e -> e.location().position()).containsExactly("E3");
        assertThat(s.diagnostics().issues()).extracting(ParseIssue::code).containsExactly("MERGE_CROSSES_BOUNDARY");
    }

    @Test
    void slotThatIsNotTwoRowsHighIsSkippedWithAnError() {
        WorkbookFixture f = WorkbookFixture.xlsx()
                .sheet("1 курс", SEMESTER, "Г-2", 1)
                .day("СРЕДА").slot("08.00-09.35", 3).slot("09.45-11.20", 2)
                .put("C3", LESSON).put("C6", LESSON);

        ParsedSchedule s = parse(f);

        assertThat(s.entries()).extracting(e -> e.location().position()).containsExactly("C6");
        assertThat(s.diagnostics().issues()).extracting(ParseIssue::code).containsExactly("UNEXPECTED_SLOT_HEIGHT");
    }

    // ---- lessons shared by several groups (streams) -----------------------------------------------------

    @Test
    void cellMergedOverWholeNeighbouringGroupsIsRepeatedForEachOfThem() {
        ParsedSchedule s = parse(standard().put("C3", LESSON).merge("C3:E4"));

        assertThat(s.entries()).hasSize(2);
        assertThat(s.entries()).extracting(ParsedEntry::groupCode).containsExactly("Г-1", "Г-2");
        assertThat(s.entries()).allSatisfy(e -> {
            assertScope(e, WeekType.EVERY_WEEK, null);
            assertThat(e.location().position()).isEqualTo("C3:E4");
        });
        assertThat(s.diagnostics().issues()).isEmpty();
    }

    @Test
    void sharedLessonOfOneWeekOnlyKeepsItsWeekForEveryGroup() {
        ParsedSchedule s = parse(standard().put("C3", LESSON).merge("C3:E3").put("C4", LESSON).merge("C4:E4"));

        assertThat(s.entries()).filteredOn(e -> e.weekType() == WeekType.NUMERATOR).extracting(ParsedEntry::groupCode)
                .containsExactly("Г-1", "Г-2");
        assertThat(s.entries()).filteredOn(e -> e.weekType() == WeekType.DENOMINATOR).extracting(ParsedEntry::groupCode)
                .containsExactly("Г-1", "Г-2");
    }

    @Test
    void sharedLessonCanCoverMoreThanTwoGroups() {
        WorkbookFixture f = WorkbookFixture.xlsx()
                .sheet("1 курс", SEMESTER, "А", 1, "Б", 2, "В", 1)
                .day("СРЕДА").slot("08.00-09.35", 2)
                .put("C3", LESSON).merge("C3:F4");

        assertThat(parse(f).entries()).extracting(ParsedEntry::groupCode).containsExactly("А", "Б", "В");
    }

    @Test
    void mergeThatCutsThroughAGroupIsStillAnError() {
        // D3:E3 starts in the second column of Г-1 and ends in Г-2
        ParsedSchedule s = parse(standard().put("D3", LESSON).merge("D3:E3").put("E4", LESSON));

        assertThat(s.entries()).extracting(e -> e.location().position()).containsExactly("E4");
        assertThat(s.diagnostics().issues()).extracting(ParseIssue::code).containsExactly("MERGE_CROSSES_BOUNDARY");
    }

    @Test
    void mergeEndingInTheMiddleOfAnotherGroupIsStillAnError() {
        WorkbookFixture f = WorkbookFixture.xlsx()
                .sheet("1 курс", SEMESTER, "А", 1, "Б", 2)
                .day("СРЕДА").slot("08.00-09.35", 2)
                .put("C3", LESSON).merge("C3:D3").put("E4", LESSON);

        ParsedSchedule s = parse(f);

        assertThat(s.diagnostics().issues()).extracting(ParseIssue::code).containsExactly("MERGE_CROSSES_BOUNDARY");
    }

    // ---- days, time, period, values ----------------------------------------------------------------------

    @Test
    void readsDayAndTimeIgnoringCaseAndTrailingSpaces() {
        ParsedSchedule s = parse(standard().put("C3", LESSON).put("C5", LESSON));

        ParsedEntry first = entryAt(s, "C3");
        assertThat(first.dayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(first.startTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(first.endTime()).isEqualTo(LocalTime.of(9, 35));
        assertThat(entryAt(s, "C5").startTime()).isEqualTo(LocalTime.of(9, 45));
    }

    @Test
    void readsSeveralDaysOnOneSheet() {
        WorkbookFixture f = WorkbookFixture.xlsx()
                .sheet("1 курс", SEMESTER, "Г-2", 1)
                .day("ВТОРНИК").slot("08.00-09.35", 2)
                .day("суббота").slot("08.00-09.35", 2)
                .put("C3", LESSON).put("C5", LESSON);

        ParsedSchedule s = parse(f);

        assertThat(entryAt(s, "C3").dayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
        assertThat(entryAt(s, "C5").dayOfWeek()).isEqualTo(DayOfWeek.SATURDAY);
    }

    @Test
    void readsSemesterFromHeaderText() {
        ParsedSchedule s = parse(standard().put("C3", LESSON));

        assertThat(s.period().yearFrom()).isEqualTo(2025);
        assertThat(s.period().yearTo()).isEqualTo(2026);
        assertThat(s.period().term()).isEqualTo(AcademicTerm.SPRING);
        assertThat(s.parserKey()).isEqualTo("vsuet-grid-xlsx");
        assertThat(s.diagnostics().parserVersion()).isEqualTo(parser.version());
    }

    @Test
    void autumnSemesterIsRecognised() {
        WorkbookFixture f = WorkbookFixture.xlsx()
                .sheet("1 курс", "осеннего семестра 2026-2027 учебного года", "Г-2", 1)
                .day("СРЕДА").slot("08.00-09.35", 2).put("C3", LESSON);

        assertThat(parse(f).period().term()).isEqualTo(AcademicTerm.AUTUMN);
    }

    @Test
    void emptyStringsAndZeroAreTreatedAsEmptyCellsWithoutNoise() {
        ParsedSchedule s = parse(standard().put("C3", "").put("E3", 0).put("D3", LESSON));

        assertThat(s.entries()).hasSize(1);
        assertThat(s.diagnostics().issues()).isEmpty();
    }

    @Test
    void textHiddenInsideAMergedRangeIsReportedNotSilentlyDropped() {
        ParsedSchedule s = parse(standard().put("C3", LESSON).merge("C3:D4").put("D4", "пр.Скрытое\nПетров П.П.   1"));

        assertThat(s.entries()).hasSize(1);
        assertThat(s.diagnostics().issues()).singleElement().satisfies(i -> {
            assertThat(i.code()).isEqualTo("HIDDEN_TEXT_IN_MERGED_CELL");
            assertThat(i.rawText()).contains("Скрытое");
        });
    }

    @Test
    void unknownDayNameIsReportedAndItsRowsAreNotRead() {
        WorkbookFixture f = WorkbookFixture.xlsx()
                .sheet("1 курс", SEMESTER, "Г-2", 1)
                .day("ВТОРНИК").slot("08.00-09.35", 2)
                .day("ПОНЕДЕЛЬНИК/ВТОРНИК").slot("08.00-09.35", 2)
                .put("C3", LESSON).put("C5", LESSON);

        ParsedSchedule s = parse(f);

        assertThat(s.entries()).extracting(e -> e.location().position()).containsExactly("C3");
        assertThat(s.diagnostics().issues()).extracting(ParseIssue::code).containsExactly("UNKNOWN_DAY");
    }

    @Test
    void unreadableTimeIntervalIsReportedAndItsRowsAreNotRead() {
        WorkbookFixture f = WorkbookFixture.xlsx()
                .sheet("1 курс", SEMESTER, "Г-2", 1)
                .day("ВТОРНИК").slot("утро", 2).slot("09.45-11.20", 2)
                .put("C3", LESSON).put("C5", LESSON);

        ParsedSchedule s = parse(f);

        assertThat(s.entries()).extracting(e -> e.location().position()).containsExactly("C5");
        assertThat(s.diagnostics().issues()).extracting(ParseIssue::code).containsExactly("UNPARSEABLE_TIME");
    }

    // ---- diagnostics from cell text ----------------------------------------------------------------------

    @Test
    void subgroupMarkerInTextThatContradictsPositionIsAWarning() {
        ParsedSchedule s = parse(standard().put("C3", "пр.Физика - 2 п/г\nИванов И.И.   12"));

        assertThat(s.entries()).hasSize(1);
        assertThat(s.diagnostics().issues()).singleElement().satisfies(i -> {
            assertThat(i.code()).isEqualTo("SUBGROUP_MISMATCH");
            assertThat(i.location().position()).isEqualTo("C3");
        });
    }

    @Test
    void subgroupMarkerThatMatchesPositionIsFine() {
        ParsedSchedule s = parse(standard().put("D3", "пр.Физика - 2 п/г\nИванов И.И.   12"));

        assertThat(s.diagnostics().issues()).isEmpty();
    }

    @Test
    void unknownLessonMarkerIsKeptAndWarnedWithLocation() {
        ParsedSchedule s = parse(standard().put("E3", "сем.Физика\nИванов И.И.   12"));

        assertThat(s.entries()).hasSize(1);
        assertThat(s.diagnostics().issues()).singleElement().satisfies(i -> {
            assertThat(i.code()).isEqualTo("UNKNOWN_LESSON_MARKER");
            assertThat(i.location().container()).isEqualTo("1 курс");
            assertThat(i.location().position()).isEqualTo("E3");
        });
    }

    @Test
    void markerWithoutSubjectIsSkippedAndCounted() {
        ParsedSchedule s = parse(standard().put("E3", "пр.").put("E4", LESSON));

        assertThat(s.entries()).hasSize(1);
        assertThat(s.diagnostics().skipped()).isEqualTo(1);
        assertThat(s.diagnostics().totalFound()).isEqualTo(2);
    }

    // ---- several sheets / formats ------------------------------------------------------------------------

    @Test
    void combinesSheetsAndAcceptsASheetWithoutSemesterText() {
        WorkbookFixture f = WorkbookFixture.xlsx()
                .sheet("1 курс", SEMESTER, "Г-1", 2)
                .day("ВТОРНИК").slot("08.00-09.35", 2).put("C3", LESSON)
                .sheet("5 курс + магистры", null, "М-1", 1)
                .day("ВТОРНИК").slot("08.00-09.35", 2).put("C3", LESSON)
                .emptySheet("Лист1");

        ParsedSchedule s = parse(f);

        assertThat(s.groups()).extracting(g -> g.code()).containsExactly("Г-1", "М-1");
        assertThat(s.entries()).extracting(e -> e.location().container() + "!" + e.groupCode())
                .containsExactly("1 курс!Г-1", "5 курс + магистры!М-1");
        assertThat(s.diagnostics().issues()).extracting(ParseIssue::code).containsExactly("EMPTY_SHEET_IGNORED");
    }

    @Test
    void readsTheLegacyXlsFormatThroughTheSameCode() {
        WorkbookFixture f = WorkbookFixture.xls()
                .sheet("1 курс", SEMESTER, "Г-1", 2)
                .day("ВТОРНИК").slot("08.00-09.35", 2).put("D3", LESSON);

        assertScope(entryAt(parse(f), "D3"), WeekType.NUMERATOR, 2);
    }

    // ---- fatal structural problems -----------------------------------------------------------------------

    @Test
    void notAnExcelFileFailsTheImport() {
        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream("not excel".getBytes())))
                .isInstanceOf(ScheduleParseException.class)
                .hasMessageContaining("Cannot read");
    }

    @Test
    void nonEmptySheetWithoutHeaderFailsTheImport() {
        WorkbookFixture f = standard().put("C3", LESSON).sheetWithoutHeader("Прочее");

        assertThatThrownBy(() -> parse(f))
                .isInstanceOf(ScheduleParseException.class)
                .hasMessageContaining("Прочее");
    }

    @Test
    void missingSemesterFailsTheImport() {
        WorkbookFixture f = WorkbookFixture.xlsx().sheet("1 курс", null, "Г-1", 2)
                .day("ВТОРНИК").slot("08.00-09.35", 2).put("C3", LESSON);

        assertThatThrownBy(() -> parse(f))
                .isInstanceOf(ScheduleParseException.class)
                .hasMessageContaining("Semester");
    }

    @Test
    void sheetsThatDisagreeAboutTheSemesterFailTheImport() {
        WorkbookFixture f = WorkbookFixture.xlsx()
                .sheet("1 курс", SEMESTER, "Г-1", 2).day("ВТОРНИК").slot("08.00-09.35", 2).put("C3", LESSON)
                .sheet("2 курс", "осеннего семестра 2025-2026 учебного года", "Г-2", 2)
                .day("ВТОРНИК").slot("08.00-09.35", 2).put("C3", LESSON);

        assertThatThrownBy(() -> parse(f))
                .isInstanceOf(ScheduleParseException.class)
                .hasMessageContaining("disagree");
    }

    @Test
    void sameGroupOnTwoSheetsFailsTheImport() {
        WorkbookFixture f = WorkbookFixture.xlsx()
                .sheet("1 курс", SEMESTER, "Г-1", 2).day("ВТОРНИК").slot("08.00-09.35", 2).put("C3", LESSON)
                .sheet("2 курс", SEMESTER, "Г-1", 2).day("ВТОРНИК").slot("08.00-09.35", 2).put("C3", LESSON);

        assertThatThrownBy(() -> parse(f))
                .isInstanceOf(ScheduleParseException.class)
                .hasMessageContaining("Г-1");
    }

    @Test
    void workbookWithoutAnyLessonFailsTheImport() {
        assertThatThrownBy(() -> parse(standard()))
                .isInstanceOf(ScheduleParseException.class)
                .hasMessageContaining("No lessons");
    }

    @Test
    void failureCarriesDiagnosticsCollectedSoFar() {
        WorkbookFixture f = WorkbookFixture.xlsx().sheet("1 курс", null, "Г-1", 2)
                .day("ВТОРНИК").slot("08.00-09.35", 2).put("C3", "пр.").put("D3", LESSON);

        assertThatThrownBy(() -> parse(f)).isInstanceOfSatisfying(ScheduleParseException.class, e -> {
            assertThat(e.diagnostics()).isNotNull();
            assertThat(e.diagnostics().skipped()).isEqualTo(1);
        });
    }
}
