package ru.vsuet.schedule.importer.parser.zao;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ru.vsuet.schedule.importer.parser.IssueSeverity;
import ru.vsuet.schedule.importer.parser.ParseContext;
import ru.vsuet.schedule.importer.parser.ParseIssue;
import ru.vsuet.schedule.importer.parser.ParsedEntry;
import ru.vsuet.schedule.importer.parser.ParsedGroup;
import ru.vsuet.schedule.importer.parser.ParsedSchedule;
import ru.vsuet.schedule.schedule.domain.LessonType;

/** The real zao/5_kurs.xlsx of autumn 2026-2027 (sessions 12 Oct 2026 - 16 Jan 2027), parsed as a source pinned to it. */
class ZaoExcelParserRealFileTest {

    private static ParsedSchedule schedule;

    @BeforeAll
    static void parseRealFile() throws IOException {
        try (InputStream in = ZaoExcelParserRealFileTest.class.getResourceAsStream("/zao/5_kurs.xlsx")) {
            schedule = new ZaoExcelParser().parse(in, new ParseContext(LocalDate.of(2026, 9, 1)));
        }
    }

    private static ParsedEntry only(String group, Predicate<ParsedEntry> filter) {
        List<ParsedEntry> found = schedule.entries().stream().filter(e -> e.groupCode().equals(group)).filter(filter).toList();
        assertThat(found).as("entries of %s matching the filter", group).hasSize(1);
        return found.getFirst();
    }

    private static Predicate<ParsedEntry> at(String range) {
        return e -> e.location().position().equals(range);
    }

    @Test
    void theFileStatesNeitherSemesterNorCourse() {
        assertThat(schedule.period()).isNull();
        assertThat(schedule.groups()).extracting(ParsedGroup::course).containsOnlyNulls();
    }

    @Test
    void hasAllSeventeenGroupsOfTheFifthCourse() {
        assertThat(schedule.groups()).extracting(ParsedGroup::code).containsExactly(
                "ЗАП-22", "ЗМО-22", "ЗТТ-22", "ЗЭТ-22", "ЗВС-22", "ЗЖС-22", "ЗРС-22", "ЗУК-22", "ЗИП-22", "ЗИТ-22",
                "ЗУТ-22", "ЗТБ-22", "ЗХВ-22", "ЗЭР-22", "ЗГД-22", "ЗТП-22", "ЗТР-22");
    }

    @Test
    void everyLessonCellIsAccountedForAndNothingIsRejected() {
        // 882 non-empty subject cells were counted independently with plain POI (one per group and slot)
        assertThat(schedule.entries()).hasSize(882);
        assertThat(schedule.diagnostics().recognized()).isEqualTo(882);
        assertThat(schedule.diagnostics().skipped()).isZero();
        assertThat(schedule.diagnostics().issues()).noneMatch(i -> i.severity() == IssueSeverity.ERROR);
    }

    @Test
    void theOnlyWarningsAreTheEmptySheetsAndSixLeftoverRoomsOutsideTheGroups() {
        assertThat(schedule.diagnostics().issues()).extracting(ParseIssue::code)
                .containsOnly("EMPTY_SHEET_IGNORED", "CELLS_OUTSIDE_GROUPS_IGNORED");
        assertThat(schedule.diagnostics().issues()).filteredOn(i -> i.code().equals("CELLS_OUTSIDE_GROUPS_IGNORED"))
                .singleElement().satisfies(i -> {
                    assertThat(i.message()).startsWith("6 non-empty cell(s)");
                    assertThat(i.location().position()).isEqualTo("D569");
                });
    }

    @Test
    void everyLessonHasADateWhoseWeekdayAgreesWithItsDay() {
        assertThat(schedule.entries()).allSatisfy(e -> {
            assertThat(e.date()).isNotNull();
            assertThat(e.date().getDayOfWeek()).isEqualTo(e.dayOfWeek());
        });
        assertThat(schedule.entries()).extracting(ParsedEntry::date)
                .allMatch(d -> !d.isBefore(LocalDate.of(2026, 10, 12)) && !d.isAfter(LocalDate.of(2027, 1, 16)));
    }

    @Test
    void lessonTypesIncludeExamsAndCredits() {
        Map<LessonType, Long> byType = schedule.entries().stream()
                .collect(Collectors.groupingBy(ParsedEntry::lessonType, Collectors.counting()));

        assertThat(byType).containsEntry(LessonType.LECTURE, 335L).containsEntry(LessonType.PRACTICE, 242L)
                .containsEntry(LessonType.LAB, 187L).containsEntry(LessonType.EXAM, 55L).containsEntry(LessonType.CREDIT, 63L)
                .doesNotContainKey(LessonType.OTHER);
    }

    @Test
    void firstLessonOfTheFileMondayTwelfthOfOctober() {
        ParsedEntry e = only("ЗТР-22", at("BA7:BC8"));

        assertThat(e.date()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(e.dayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(e.startTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(e.endTime()).isEqualTo(LocalTime.of(9, 35));
        assertThat(e.lessonType()).isEqualTo(LessonType.LECTURE);
        assertThat(e.subject()).isEqualTo("Организация и планирование деятельности предприятий индустрии гостеприимства");
        assertThat(e.teacherText()).isEqualTo("Зацепилина Н.П.");
        assertThat(e.roomText()).isEqualTo("5-37");
    }

    @Test
    void examIsAnExamAndKeepsItsRoom() {
        ParsedEntry e = only("ЗТР-22", at("BA79:BC80"));

        assertThat(e.lessonType()).isEqualTo(LessonType.EXAM);
        assertThat(e.subject()).startsWith("Организация и планирование деятельности");
        assertThat(e.roomText()).isEqualTo("5-37");
    }

    @Test
    void twoParallelLabsOfOneGroupAreOneLessonWithBothTeachersAndRooms() {
        ParsedEntry e = only("ЗТП-22", at("AX223:AZ224"));

        assertThat(e.date()).isEqualTo(LocalDate.of(2026, 10, 29));
        assertThat(e.lessonType()).isEqualTo(LessonType.LAB);
        assertThat(e.subject()).isEqualTo("Основы диетологии и нутрициологии / Пищевые и биологически активные добавки");
        assertThat(e.teacherText()).isEqualTo("Карлов Д.А., Выродов Д.М.");
        assertThat(e.roomText()).isEqualTo("20б, 20з");
    }

    @Test
    void subgroupsComeFromTheTextAndAreRemovedFromTheSubject() {
        List<ParsedEntry> withSubgroup = schedule.entries().stream().filter(e -> e.subgroup() != null).toList();

        assertThat(withSubgroup).hasSize(108);
        assertThat(withSubgroup).extracting(ParsedEntry::subgroup).containsOnly(1, 2);
        assertThat(withSubgroup).allSatisfy(e -> assertThat(e.subject()).doesNotContain("п/г"));
        assertThat(schedule.entries()).extracting(ParsedEntry::subject).noneMatch(s -> s.contains("п/г"));
    }

    @Test
    void everyEntryKeepsItsRawTextAndItsLocation() {
        assertThat(schedule.entries()).allSatisfy(e -> {
            assertThat(e.rawText()).isNotBlank();
            assertThat(e.location().container()).isEqualTo("Список 8");
            assertThat(e.location().position()).matches("[A-Z]+\\d+:[A-Z]+\\d+");
            assertThat(e.teacherText()).isNotBlank();
            assertThat(e.roomText()).isNotBlank();
        });
    }
}
