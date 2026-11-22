package ru.vsuet.schedule.importer.parser.spo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ru.vsuet.schedule.academic.domain.AcademicTerm;
import ru.vsuet.schedule.common.WeekType;
import ru.vsuet.schedule.importer.parser.IssueSeverity;
import ru.vsuet.schedule.importer.parser.ParseIssue;
import ru.vsuet.schedule.importer.parser.ParsedEntry;
import ru.vsuet.schedule.importer.parser.ParsedGroup;
import ru.vsuet.schedule.importer.parser.ParsedSchedule;
import ru.vsuet.schedule.schedule.domain.LessonType;

/** The two real SPO files (autumn 2026-2027) as downloaded from vsuet.ru/images/student/schedule/fspo/. */
class SpoExcelParserRealFilesTest {

    private static ParsedSchedule firstYear;
    private static ParsedSchedule secondToFourthYear;

    @BeforeAll
    static void parseBothFiles() throws IOException {
        firstYear = parse("/spo/1_kurs.xls");
        secondToFourthYear = parse("/spo/2-3-4_kurs.xls");
    }

    private static ParsedSchedule parse(String resource) throws IOException {
        try (InputStream in = SpoExcelParserRealFilesTest.class.getResourceAsStream(resource)) {
            return new SpoExcelParser().parse(in);
        }
    }

    private static ParsedEntry only(ParsedSchedule s, String group, String position) {
        List<ParsedEntry> found = s.entries().stream()
                .filter(e -> e.groupCode().equals(group)).filter(e -> e.location().position().equals(position)).toList();
        assertThat(found).as("entries of %s at %s", group, position).hasSize(1);
        return found.getFirst();
    }

    private static List<String> errorCodes(ParsedSchedule s) {
        return s.diagnostics().issues().stream().filter(i -> i.severity() == IssueSeverity.ERROR).map(ParseIssue::code).toList();
    }

    // ---- 1_kurs.xls --------------------------------------------------------------------------------------

    @Test
    void firstYearFileStatesItsSemester() {
        assertThat(firstYear.period().term()).isEqualTo(AcademicTerm.AUTUMN);
        assertThat(firstYear.period().yearFrom()).isEqualTo(2026);
        assertThat(firstYear.period().yearTo()).isEqualTo(2027);
    }

    @Test
    void firstYearFileHasAllNineteenGroups() {
        assertThat(firstYear.groups()).extracting(ParsedGroup::code).containsExactly(
                "ТХС-261", "ХТ-261", "УК-260", "УК-261", "ПКД-267", "ПКД-268", "ПКД-269", "ТМ-267", "ТХ-263", "КС-263",
                "ОИС-269", "РПО-265", "РПО-266", "РПО-267", "РПО-268", "ТГ-266", "ТГ-267", "ОДЛ-264", "ЭБ-262");
    }

    @Test
    void firstYearFileEveryVisibleLessonCellIsAccountedFor() {
        // Counted independently with plain POI: 351 visible non-empty cells reach 405 (cell, group) pairs. Columns
        // T, X, AK:AN, AU:AV, AY:BB are hidden in Excel; 13 non-empty cells in them are stale leftovers.
        assertThat(firstYear.diagnostics().skipped()).isZero();
        assertThat(errorCodes(firstYear)).isEmpty();
        assertThat(firstYear.entries()).hasSize(405);
        assertThat(firstYear.diagnostics().issues()).filteredOn(i -> i.code().equals("HIDDEN_CELLS_IGNORED"))
                .singleElement().satisfies(i -> assertThat(i.message()).startsWith("13 non-empty cell(s)"));
    }

    @Test
    void lessonMergedOverHiddenColumnsIsAWholeGroupLessonOfItsVisibleColumns() {
        // AI63:AL63 reaches into the hidden AK:AL, which belong to no group: it is just the whole of РПО-268 (AI:AJ)
        ParsedEntry e = only(firstYear, "РПО-268", "AI63:AL63");

        assertThat(e.subgroup()).isNull();
        assertThat(firstYear.diagnostics().issues()).extracting(ParseIssue::code).doesNotContain("PARTIAL_SPAN_AS_WHOLE_GROUP");
    }

    @Test
    void lessonsInHiddenColumnsOfAGroupAreNotShown() {
        // ЭБ-262 is AW:AZ but AY:AZ are hidden: the stale "МАТЕМАТИКА" cells AY68:AZ69 and AY70:AZ71 must not appear
        assertThat(firstYear.entries()).filteredOn(e -> e.groupCode().equals("ЭБ-262"))
                .extracting(e -> e.location().position()).noneMatch(p -> p.startsWith("AY"));
        assertThat(firstYear.entries()).filteredOn(e -> e.groupCode().equals("ЭБ-262"))
                .extracting(e -> e.location().position()).contains("AW60:AX61");
    }

    @Test
    void numeratorAndDenominatorLessonsOfTheFirstGroupOnMonday() {
        ParsedEntry numerator = only(firstYear, "ТХС-261", "C52:D52");
        ParsedEntry denominator = only(firstYear, "ТХС-261", "C53:D53");

        assertThat(numerator.dayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(numerator.startTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(numerator.endTime()).isEqualTo(LocalTime.of(9, 35));
        assertThat(numerator.weekType()).isEqualTo(WeekType.NUMERATOR);
        assertThat(numerator.lessonType()).isEqualTo(LessonType.PRACTICE);
        assertThat(numerator.subject()).isEqualTo("ХИМИЯ");
        assertThat(numerator.teacherText()).isEqualTo("Воронцов И.Н.");
        assertThat(numerator.roomText()).isEqualTo("33");
        assertThat(numerator.subgroup()).isNull();
        assertThat(denominator.weekType()).isEqualTo(WeekType.DENOMINATOR);
        assertThat(denominator.lessonType()).isEqualTo(LessonType.LAB);
    }

    @Test
    void lessonOverTwoRowsIsEveryWeek() {
        ParsedEntry e = only(firstYear, "УК-260", "G52:H53");

        assertThat(e.weekType()).isEqualTo(WeekType.EVERY_WEEK);
        assertThat(e.subject()).isEqualTo("ИНФОРМАТИКА");
        assertThat(e.teacherText()).isEqualTo("Миронов А.Н.");
        assertThat(e.roomText()).isEqualTo("19");
    }

    @Test
    void firstYearFileHasExplicitIntervalsIncludingTheUnusualOnes() {
        assertThat(firstYear.entries()).extracting(e -> e.startTime() + "-" + e.endTime()).contains(
                "08:00-09:35", "09:45-11:20", "11:30-13:05", "11:50-13:25", "13:15-14:50", "13:35-15:10");
        assertThat(firstYear.diagnostics().issues()).extracting(ParseIssue::code).doesNotContain("SLOT_END_DERIVED");
    }

    // ---- 2-3-4_kurs.xls ----------------------------------------------------------------------------------

    @Test
    void secondToFourthYearFileDoesNotStateItsSemester() {
        assertThat(secondToFourthYear.period()).isNull();
    }

    @Test
    void secondToFourthYearFileHasFiftySixVisibleGroupsWithNormalisedCodes() {
        // 8 more groups (ЛКК-2111 ... К-212) stand in hidden columns D:R and are not part of the schedule
        assertThat(secondToFourthYear.groups()).hasSize(56);
        assertThat(secondToFourthYear.groups()).extracting(ParsedGroup::code)
                .contains("ИСП-255", "ИСП-2510", "ТГ-249")
                .doesNotContain("ИСП -255", "ЛКК-2111", "ИСП-216 б");
    }

    @Test
    void groupsInHiddenColumnsAreIgnoredAndReported() {
        assertThat(secondToFourthYear.diagnostics().issues()).filteredOn(i -> i.code().equals("HIDDEN_GROUPS_IGNORED"))
                .singleElement().satisfies(i -> assertThat(i.message()).startsWith("8 group(s)")
                        .contains("ЛКК-2111", "ПКД-218", "ПКД-219", "ИСП-215", "ИСП-216", "ИСП-216 б", "КС-213", "К-212"));
    }

    @Test
    void firstYearGroupsTakeTheCourseFromTheTitleOverTheWholeSheet() {
        // B18:AZ18 "РАСПИСАНИЕ УЧЕБНЫХ ЗАНЯТИЙ 1 КУРСА ФСПО ..."
        assertThat(firstYear.groups()).extracting(ParsedGroup::course).containsOnly(1);
        assertThat(firstYear.diagnostics().issues()).extracting(ParseIssue::code).doesNotContain("GROUP_COURSE_UNKNOWN");
    }

    @Test
    void secondToFourthYearGroupsTakeTheCourseFromTheMergedBandAboveThem() {
        // row 29: S29:BF31 "2 КУРС", BG29:CZ31 "3 КУРС", DA29:DZ31 "4 КУРС"
        assertThat(course("ТХС-251")).isEqualTo(2);
        assertThat(course("ТХ-254")).isEqualTo(2);   // last group under the 2nd-course band
        assertThat(course("ТХС-241")).isEqualTo(3);   // first group under the 3rd-course band
        assertThat(course("ПКД-249")).isEqualTo(3);
        assertThat(course("ТХС-231")).isEqualTo(4);
        assertThat(course("ПКД-239")).isEqualTo(4);   // header DY:EB, but EA:EB are hidden: visible DY:DZ is inside DA:DZ
        assertThat(secondToFourthYear.groups()).filteredOn(g -> g.course() == 2).hasSize(20);
        assertThat(secondToFourthYear.groups()).filteredOn(g -> g.course() == 3).hasSize(23);
        assertThat(secondToFourthYear.groups()).filteredOn(g -> g.course() == 4).hasSize(13);
    }

    @Test
    void everyVisibleGroupOfTheFileHasACourse() {
        // the groups that used to lack one stood in hidden columns D:R
        assertThat(secondToFourthYear.groups()).extracting(ParsedGroup::course).doesNotContainNull();
        assertThat(secondToFourthYear.diagnostics().issues()).extracting(ParseIssue::code).doesNotContain("GROUP_COURSE_UNKNOWN");
    }

    private static Integer course(String group) {
        return secondToFourthYear.groups().stream().filter(g -> g.code().equals(group)).findFirst().orElseThrow().course();
    }

    @Test
    void secondToFourthYearFileEveryVisibleLessonCellIsAccountedFor() {
        // Counted independently with plain POI: 984 visible non-empty cells (41 of them time labels) reach 1144
        // (cell, group) pairs. Rows 116-119 and columns D:R, EA:EC are hidden; 18 non-empty cells in them are ignored,
        // among them the lesson AO116:AP117 whose time cell was shifted, and the merge DY86:EC87 that reached hidden EA:EC.
        assertThat(secondToFourthYear.diagnostics().skipped()).isZero();
        assertThat(errorCodes(secondToFourthYear)).isEmpty();
        assertThat(secondToFourthYear.entries()).hasSize(1144);
        assertThat(secondToFourthYear.diagnostics().issues()).filteredOn(i -> i.code().equals("HIDDEN_CELLS_IGNORED"))
                .singleElement().satisfies(i -> assertThat(i.message()).startsWith("18 non-empty cell(s)"));
    }

    @Test
    void stackedSubgroupLabsAreNumeratorForTheFirstAndDenominatorForTheSecondSubgroup() {
        ParsedEntry first = only(secondToFourthYear, "ПКД-257", "AA34:AB34");
        ParsedEntry second = only(secondToFourthYear, "ПКД-257", "AA35:AB35");

        assertThat(first.subject()).isEqualTo("ХИМИЯ");
        assertThat(first.lessonType()).isEqualTo(LessonType.LAB);   // written "лоаб." in the file
        assertThat(first.subgroup()).isEqualTo(1);
        assertThat(first.weekType()).isEqualTo(WeekType.NUMERATOR);
        assertThat(first.teacherText()).isEqualTo("Черноусова О.В.");
        assertThat(first.roomText()).isEqualTo("25");
        assertThat(second.subgroup()).isEqualTo(2);
        assertThat(second.weekType()).isEqualTo(WeekType.DENOMINATOR);
    }

    @Test
    void sharedLessonIsRepeatedForEveryGroupItCovers() {
        // U34:X35 = "МДК 04.01 ..." over groups УК-250 and УК-251
        List<ParsedEntry> shared = secondToFourthYear.entries().stream()
                .filter(e -> e.location().position().equals("U34:X35")).toList();

        assertThat(shared).extracting(ParsedEntry::groupCode).containsExactly("УК-250", "УК-251");
        assertThat(shared).allSatisfy(e -> {
            assertThat(e.weekType()).isEqualTo(WeekType.EVERY_WEEK);
            assertThat(e.teacherText()).isEqualTo("Фомичев Г.М.");
            assertThat(e.roomText()).isEqualTo("С и В");
        });
    }

    @Test
    void slotsThatStateOnlyTheStartGetTheEndOnePairLater() {
        Predicate<ParsedEntry> saturdayNineThirty = e -> e.startTime().equals(LocalTime.of(11, 30));

        assertThat(secondToFourthYear.entries()).filteredOn(saturdayNineThirty).isNotEmpty()
                .allSatisfy(e -> assertThat(e.endTime()).isEqualTo(LocalTime.of(13, 5)));
        assertThat(secondToFourthYear.diagnostics().issues()).extracting(ParseIssue::code).contains("SLOT_END_DERIVED");
    }

    @Test
    void everyEntryOfBothFilesKeepsItsRawTextAndAReadableSubject() {
        List<ParsedEntry> all = new java.util.ArrayList<>(firstYear.entries());
        all.addAll(secondToFourthYear.entries());

        assertThat(all).allSatisfy(e -> {
            assertThat(e.rawText()).isNotBlank();
            assertThat(e.subject()).isNotBlank().doesNotContain("  ");
            assertThat(e.location().container()).isEqualTo("Лист3");
        });
    }
}
