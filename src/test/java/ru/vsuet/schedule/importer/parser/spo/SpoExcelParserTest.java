package ru.vsuet.schedule.importer.parser.spo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import ru.vsuet.schedule.academic.domain.AcademicTerm;
import ru.vsuet.schedule.common.WeekType;
import ru.vsuet.schedule.importer.parser.ParseIssue;
import ru.vsuet.schedule.importer.parser.ParsedEntry;
import ru.vsuet.schedule.importer.parser.ParsedSchedule;
import ru.vsuet.schedule.importer.parser.ScheduleParseException;
import ru.vsuet.schedule.importer.parser.fuits.WorkbookFixture;
import ru.vsuet.schedule.schedule.domain.LessonType;

/**
 * SPO layout on small hand-built workbooks (see {@link WorkbookFixture#sheetSpo}). In the fixtures, group "А-1"
 * is columns C:D, "Б-1" is E:F, "В-1" (when used) is four columns. First slot = rows 5 (numerator) and 6
 * (denominator), second slot = rows 7 and 8.
 */
class SpoExcelParserTest {

    private static final String SEMESTER = "РАСПИСАНИЕ  УЧЕБНЫХ  ЗАНЯТИЙ   1  КУРСА   ФСПО   НА  ОСЕННИЙ  СЕМЕСТР 2026 - 2027 учебного года";
    private static final String CHEMISTRY = "ХИМИЯ    (практические занятия)         Воронцов И.Н.,      а. 33";

    private final SpoExcelParser parser = new SpoExcelParser();

    private static WorkbookFixture standard(String semester) {
        return WorkbookFixture.xlsx().sheetSpo("Лист3", semester, "А-1", 2, "Б-1", 2)
                .day("ПОНЕДЕЛЬНИК").slot("8.00-9.35", 2).slot("9.45-11.20", 2);
    }

    private ParsedSchedule parse(WorkbookFixture f) {
        return parser.parse(f.stream());
    }

    private static ParsedEntry at(ParsedSchedule s, String position) {
        return s.entries().stream().filter(e -> e.location().position().equals(position)).findFirst()
                .orElseThrow(() -> new AssertionError("no entry at " + position + " in " + s.entries()));
    }

    @Test
    void readsHeaderWithLabelsMergedOverSeveralRowsAndGroupsOneRowLower() {
        ParsedSchedule s = parse(standard(SEMESTER).put("C5", CHEMISTRY).merge("C5:D6"));

        assertThat(s.groups()).extracting(g -> g.code()).containsExactly("А-1", "Б-1");
        ParsedEntry e = at(s, "C5:D6");
        assertThat(e.subject()).isEqualTo("ХИМИЯ");
        assertThat(e.lessonType()).isEqualTo(LessonType.PRACTICE);
        assertThat(e.teacherText()).isEqualTo("Воронцов И.Н.");
        assertThat(e.roomText()).isEqualTo("33");
        assertThat(e.dayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(e.weekType()).isEqualTo(WeekType.EVERY_WEEK);
        assertThat(e.subgroup()).isNull();
    }

    @Test
    void readsTheSemesterFromTheTitleLine() {
        ParsedSchedule s = parse(standard(SEMESTER).put("C5", CHEMISTRY));

        assertThat(s.period().yearFrom()).isEqualTo(2026);
        assertThat(s.period().yearTo()).isEqualTo(2027);
        assertThat(s.period().term()).isEqualTo(AcademicTerm.AUTUMN);
        assertThat(s.parserKey()).isEqualTo("spo-xls");
    }

    @Test
    void aFileWithoutSemesterIsNotAnErrorItJustHasNoPeriod() {
        ParsedSchedule s = parse(standard(null).put("C5", CHEMISTRY));

        assertThat(s.period()).isNull();
        assertThat(s.entries()).hasSize(1);
    }

    @Test
    void timeSlotsMayStateOnlyTheStartAndTheEndIsOnePairLater() {
        WorkbookFixture f = WorkbookFixture.xlsx().sheetSpo("Лист3", SEMESTER, "А-1", 2, "Б-1", 2)
                .day("ПОНЕДЕЛЬНИК").slot("1) 8 00", 2).slot("2) 9 45", 2)
                .put("C5", CHEMISTRY).put("C7", CHEMISTRY);

        ParsedSchedule s = parse(f);

        assertThat(at(s, "C5").startTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(at(s, "C5").endTime()).isEqualTo(LocalTime.of(9, 35));
        assertThat(at(s, "C7").startTime()).isEqualTo(LocalTime.of(9, 45));
        assertThat(at(s, "C7").endTime()).isEqualTo(LocalTime.of(11, 20));
        assertThat(s.diagnostics().issues()).extracting(ParseIssue::code).containsExactly("SLOT_END_DERIVED");
    }

    @Test
    void explicitIntervalsInTheShortNotationAreReadAsWritten() {
        WorkbookFixture f = WorkbookFixture.xlsx().sheetSpo("Лист3", SEMESTER, "А-1", 2)
                .day("ПЯТНИЦА").slot("11.30-13.05", 2).slot("15.20- 16.55", 2)
                .put("C5", CHEMISTRY).put("C7", CHEMISTRY);

        ParsedSchedule s = parse(f);

        assertThat(at(s, "C5").startTime()).isEqualTo(LocalTime.of(11, 30));
        assertThat(at(s, "C5").endTime()).isEqualTo(LocalTime.of(13, 5));
        assertThat(at(s, "C7").startTime()).isEqualTo(LocalTime.of(15, 20));
        assertThat(at(s, "C7").endTime()).isEqualTo(LocalTime.of(16, 55));
        assertThat(s.diagnostics().issues()).isEmpty();
    }

    @Test
    void groupCodesAreNormalised() {
        WorkbookFixture f = WorkbookFixture.xlsx().sheetSpo("Лист3", SEMESTER, "  ИСП -255      ", 2, "ИСП-216 б   ", 2)
                .day("ВТОРНИК").slot("8.00-9.35", 2).put("C5", CHEMISTRY).put("E5", CHEMISTRY);

        assertThat(parse(f).groups()).extracting(g -> g.code()).containsExactly("ИСП-255", "ИСП-216 б");
    }

    // ---- subgroup: text wins, geometry is the fallback ---------------------------------------------------

    @Test
    void subgroupStatedInTheTextAppliesEvenWhenTheCellCoversTheWholeGroup() {
        // typical for SPO: stacked rows 1 под. (numerator week) and 2 под. (denominator week), both over the full width
        ParsedSchedule s = parse(standard(SEMESTER)
                .put("C5", "ХИМИЯ (лаб.) 1 под. Черноусова О.В., а. 25").merge("C5:D5")
                .put("C6", "ХИМИЯ (лаб.) 2 под. Черноусова О.В., а. 25").merge("C6:D6"));

        ParsedEntry first = at(s, "C5:D5");
        ParsedEntry second = at(s, "C6:D6");
        assertThat(first.subgroup()).isEqualTo(1);
        assertThat(first.weekType()).isEqualTo(WeekType.NUMERATOR);
        assertThat(first.lessonType()).isEqualTo(LessonType.LAB);
        assertThat(second.subgroup()).isEqualTo(2);
        assertThat(second.weekType()).isEqualTo(WeekType.DENOMINATOR);
        assertThat(s.diagnostics().issues()).isEmpty();
    }

    @Test
    void columnDecidesWhenTheTextNamesNoSubgroup() {
        ParsedSchedule s = parse(standard(SEMESTER).put("C5", CHEMISTRY).put("D6", CHEMISTRY));

        assertThat(at(s, "C5").subgroup()).isEqualTo(1);
        assertThat(at(s, "D6").subgroup()).isEqualTo(2);
    }

    @Test
    void textAndColumnThatDisagreeAreAWarningAndTheTextIsUsed() {
        ParsedSchedule s = parse(standard(SEMESTER).put("C5", "ХИМИЯ (лаб.) 2 под. Черноусова О.В., а. 25"));

        assertThat(at(s, "C5").subgroup()).isEqualTo(2);
        assertThat(s.diagnostics().issues()).extracting(ParseIssue::code).containsExactly("SUBGROUP_MISMATCH");
    }

    // ---- wide groups and shared lessons ------------------------------------------------------------------

    @Test
    void lessonOverSomeColumnsOfAWideGroupIsShownToTheWholeGroupWithAWarning() {
        WorkbookFixture f = WorkbookFixture.xlsx().sheetSpo("Лист3", SEMESTER, "В-1", 4)
                .day("СРЕДА").slot("8.00-9.35", 2)
                .put("C5", CHEMISTRY).merge("C5:D6");

        ParsedSchedule s = parse(f);

        assertThat(at(s, "C5:D6").subgroup()).isNull();
        assertThat(s.diagnostics().issues()).extracting(ParseIssue::code).containsExactly("PARTIAL_SPAN_AS_WHOLE_GROUP");
        assertThat(s.diagnostics().skipped()).isZero();
    }

    @Test
    void sameLessonOverSomeColumnsOfAWideGroupWithASubgroupInTheTextKeepsThatSubgroup() {
        WorkbookFixture f = WorkbookFixture.xlsx().sheetSpo("Лист3", SEMESTER, "В-1", 4)
                .day("СРЕДА").slot("8.00-9.35", 2)
                .put("E5", "ХИМИЯ (лаб.) 2 под. Иванов И.И., а. 25").merge("E5:F6");

        ParsedSchedule s = parse(f);

        assertThat(at(s, "E5:F6").subgroup()).isEqualTo(2);
        assertThat(s.diagnostics().issues()).isEmpty();
    }

    @Test
    void lessonMergedOverNeighbouringGroupsIsRepeatedForEachOfThem() {
        ParsedSchedule s = parse(standard(SEMESTER).put("C5", CHEMISTRY).merge("C5:F6"));

        assertThat(s.entries()).extracting(ParsedEntry::groupCode).containsExactly("А-1", "Б-1");
        assertThat(s.entries()).allSatisfy(e -> assertThat(e.weekType()).isEqualTo(WeekType.EVERY_WEEK));
    }

    @Test
    void sharedLessonEndingInsideAnotherGroupIncludesThatGroupWithAWarning() {
        WorkbookFixture f = WorkbookFixture.xlsx().sheetSpo("Лист3", SEMESTER, "А-1", 2, "В-1", 4)
                .day("СРЕДА").slot("8.00-9.35", 2)
                .put("C5", CHEMISTRY).merge("C5:G6");     // А-1 (C:D) whole + the first three of the four columns of В-1

        ParsedSchedule s = parse(f);

        assertThat(s.entries()).extracting(ParsedEntry::groupCode).containsExactly("А-1", "В-1");
        assertThat(s.diagnostics().issues()).extracting(ParseIssue::code).containsExactly("PARTIAL_SPAN_AS_WHOLE_GROUP");
    }

    @Test
    void lessonReachingIntoColumnsOfNoGroupIsSkippedWithAnError() {
        // C:D is the only group; the merge goes on into E:F which belong to no group
        WorkbookFixture f = WorkbookFixture.xlsx().sheetSpo("Лист3", SEMESTER, "А-1", 2)
                .day("СРЕДА").slot("8.00-9.35", 2).slot("9.45-11.20", 2)
                .put("C5", CHEMISTRY).merge("C5:F6").put("C7", CHEMISTRY);

        ParsedSchedule s = parse(f);

        assertThat(s.entries()).extracting(e -> e.location().position()).containsExactly("C7");
        assertThat(s.diagnostics().issues()).extracting(ParseIssue::code).containsExactly("MERGE_CROSSES_BOUNDARY");
        assertThat(s.diagnostics().skipped()).isEqualTo(1);
    }

    // ---- problems that must not be silent ----------------------------------------------------------------

    @Test
    void lessonTextInRowsOfNoTimeSlotIsReportedAsSkipped() {
        // rows 5-6 = slot 1, row 7 = a row of the day without any time cell, rows 8-9 = slot 2
        WorkbookFixture f = WorkbookFixture.xlsx().sheetSpo("Лист3", SEMESTER, "А-1", 2)
                .day("ЧЕТВЕРГ").slot("8.00-9.35", 2).slot("", 1).slot("9.45-11.20", 2)
                .put("C5", CHEMISTRY).put("C7", CHEMISTRY).put("C8", CHEMISTRY);

        ParsedSchedule s = parse(f);

        assertThat(s.entries()).extracting(e -> e.location().position()).containsExactly("C5", "C8");
        assertThat(s.diagnostics().skipped()).isEqualTo(1);
        assertThat(s.diagnostics().issues()).singleElement().satisfies(i -> {
            assertThat(i.code()).isEqualTo("TEXT_OUTSIDE_TIME_SLOT");
            assertThat(i.location().position()).isEqualTo("C7");
        });
    }

    @Test
    void unclassifiedTextOfACellSurfacesAsAWarningWithItsLocation() {
        ParsedSchedule s = parse(standard(SEMESTER).put("C5", "МДК.01.02 Процессы Косенко И.С. о, Иванов И.И., а. 5").merge("C5:D6"));

        assertThat(s.entries()).hasSize(1);
        assertThat(s.diagnostics().issues()).singleElement().satisfies(i -> {
            assertThat(i.code()).isEqualTo("UNPARSED_REMAINDER");
            assertThat(i.location().position()).isEqualTo("C5:D6");
        });
    }

    @Test
    void readsLegacyXls() {
        WorkbookFixture f = WorkbookFixture.xls().sheetSpo("Лист3", SEMESTER, "А-1", 2)
                .day("ПЯТНИЦА").slot("8.00-9.35", 2).put("D5", CHEMISTRY);

        ParsedSchedule s = parse(f);

        assertThat(at(s, "D5").subgroup()).isEqualTo(2);
        assertThat(at(s, "D5").weekType()).isEqualTo(WeekType.NUMERATOR);
    }

    @Test
    void notAnExcelFileFailsTheImport() {
        assertThatThrownBy(() -> parser.parse(new java.io.ByteArrayInputStream("not excel".getBytes())))
                .isInstanceOf(ScheduleParseException.class);
    }
}
