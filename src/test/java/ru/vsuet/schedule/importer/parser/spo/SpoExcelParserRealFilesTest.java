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
    void firstYearFileEveryLessonCellIsAccountedForOnlyOneLessonIsRejected() {
        // 348 lesson cells were counted independently (apache poi dump); shared lessons count once per group
        assertThat(firstYear.diagnostics().skipped()).isEqualTo(1);
        assertThat(errorCodes(firstYear)).containsExactly("MERGE_CROSSES_BOUNDARY");
        ParseIssue rejected = firstYear.diagnostics().issues().stream()
                .filter(i -> i.severity() == IssueSeverity.ERROR).findFirst().orElseThrow();
        assertThat(rejected.location().position()).isEqualTo("AI63:AL63");
        assertThat(firstYear.entries()).hasSize(417);
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
    void secondToFourthYearFileHasSixtyFourGroupsWithNormalisedCodes() {
        assertThat(secondToFourthYear.groups()).hasSize(64);
        assertThat(secondToFourthYear.groups()).extracting(ParsedGroup::code)
                .contains("ЛКК-2111", "ИСП-216 б", "ИСП-255", "ИСП-2510", "ТГ-249")
                .doesNotContain("ИСП -255");
    }

    @Test
    void secondToFourthYearFileEveryLessonCellIsAccountedFor() {
        // 943 lesson cells were counted independently; two lessons cannot be placed and are reported as errors
        assertThat(secondToFourthYear.diagnostics().skipped()).isEqualTo(2);
        assertThat(errorCodes(secondToFourthYear)).containsExactlyInAnyOrder("MERGE_CROSSES_BOUNDARY", "TEXT_OUTSIDE_TIME_SLOT");
        assertThat(secondToFourthYear.diagnostics().issues())
                .filteredOn(i -> i.code().equals("TEXT_OUTSIDE_TIME_SLOT"))
                .singleElement().satisfies(i -> assertThat(i.location().position()).isEqualTo("AO116:AP117"));
        assertThat(secondToFourthYear.entries()).hasSize(1160);
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
