package ru.vsuet.schedule.importer.parser.zao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import ru.vsuet.schedule.common.WeekType;
import ru.vsuet.schedule.importer.parser.IssueSeverity;
import ru.vsuet.schedule.importer.parser.ParseContext;
import ru.vsuet.schedule.importer.parser.ParseIssue;
import ru.vsuet.schedule.importer.parser.ParsedEntry;
import ru.vsuet.schedule.importer.parser.ParsedSchedule;
import ru.vsuet.schedule.importer.parser.ScheduleParseException;
import ru.vsuet.schedule.schedule.domain.LessonType;

/**
 * The correspondence layout on small hand-built workbooks (see {@link ZaoFixture}). Group 0 has its text in column C
 * and its rooms in E, group 1 in F and H. The first day block starts at row 2, a slot is two rows.
 */
class ZaoExcelParserTest {

    private static final ParseContext AUTUMN_2026 = new ParseContext(LocalDate.of(2026, 9, 1));
    private static final String MONDAY = "ПНД \n12/10";
    private static final String TUESDAY = "ВТР \n13/10";

    private final ZaoExcelParser parser = new ZaoExcelParser();

    private ParsedSchedule parse(ZaoFixture f) {
        return parser.parse(f.stream(), AUTUMN_2026);
    }

    private static ZaoFixture oneDay() {
        return ZaoFixture.withGroups("ЗАП-22", "ЗМО-22").day(MONDAY, "08.00-09.35", "09.45-11.20");
    }

    private static ParseIssue only(ParsedSchedule s, String code) {
        return s.diagnostics().issues().stream().filter(i -> i.code().equals(code)).findFirst()
                .orElseThrow(() -> new AssertionError("no issue " + code + " in " + s.diagnostics().issues()));
    }

    // ---- what a lesson is --------------------------------------------------------------------------------

    @Test
    void readsDateTimeSubjectTypeTeacherAndRoom() {
        ParsedSchedule s = parse(oneDay().lesson(MONDAY, 0, 0, "л. Экономическая теория", "Золотухина О.О.", "450"));

        assertThat(s.groups()).extracting(g -> g.code()).containsExactly("ЗАП-22", "ЗМО-22");
        assertThat(s.entries()).singleElement().satisfies(e -> {
            assertThat(e.groupCode()).isEqualTo("ЗАП-22");
            assertThat(e.date()).isEqualTo(LocalDate.of(2026, 10, 12));
            assertThat(e.dayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
            assertThat(e.startTime()).isEqualTo(LocalTime.of(8, 0));
            assertThat(e.endTime()).isEqualTo(LocalTime.of(9, 35));
            assertThat(e.weekType()).isEqualTo(WeekType.EVERY_WEEK);
            assertThat(e.subgroup()).isNull();
            assertThat(e.subject()).isEqualTo("Экономическая теория");
            assertThat(e.lessonType()).isEqualTo(LessonType.LECTURE);
            assertThat(e.teacherText()).isEqualTo("Золотухина О.О.");
            assertThat(e.roomText()).isEqualTo("450");
            assertThat(e.location().position()).isEqualTo("C2:E3");
        });
        assertThat(s.period()).isNull();
        assertThat(s.diagnostics().issues()).isEmpty();
    }

    @Test
    void secondSlotAndSecondGroupAreReadFromTheirOwnCells() {
        ParsedSchedule s = parse(oneDay().lesson(MONDAY, 1, 1, "пр. Физика", "Иванов И.И.", "12"));

        assertThat(s.entries()).singleElement().satisfies(e -> {
            assertThat(e.groupCode()).isEqualTo("ЗМО-22");
            assertThat(e.startTime()).isEqualTo(LocalTime.of(9, 45));
            assertThat(e.location().position()).isEqualTo("F4:H5");
        });
    }

    @Test
    void everyLessonTypeMarkerIsRecognised() {
        ParsedSchedule s = parse(ZaoFixture.withGroups("ЗАП-22").day(MONDAY, "08.00-09.35", "09.45-11.20", "11.50-13.25",
                        "13.35-15.10", "15.20-16.55")
                .lesson(MONDAY, 0, 0, "л. А", "Т.Т.", "1").lesson(MONDAY, 1, 0, "пр. Б", "Т.Т.", "1")
                .lesson(MONDAY, 2, 0, "лаб. В", "Т.Т.", "1").lesson(MONDAY, 3, 0, "экз. Г", "Т.Т.", "1")
                .lesson(MONDAY, 4, 0, "зач. Д", "Т.Т.", "1"));

        assertThat(s.entries()).extracting(ParsedEntry::lessonType).containsExactly(
                LessonType.LECTURE, LessonType.PRACTICE, LessonType.LAB, LessonType.EXAM, LessonType.CREDIT);
        assertThat(s.entries()).extracting(ParsedEntry::subject).containsExactly("А", "Б", "В", "Г", "Д");
    }

    @Test
    void unknownMarkerKeepsTheSubjectAndWarns() {
        ParsedSchedule s = parse(oneDay().lesson(MONDAY, 0, 0, "кр. Курсовая работа", "Т.Т.", "1"));

        assertThat(s.entries()).singleElement().satisfies(e -> {
            assertThat(e.lessonType()).isEqualTo(LessonType.OTHER);
            assertThat(e.subject()).isEqualTo("Курсовая работа");
        });
        assertThat(only(s, "UNKNOWN_LESSON_MARKER").severity()).isEqualTo(IssueSeverity.WARNING);
    }

    @Test
    void subjectWithoutMarkerIsAnOtherLesson() {
        ParsedSchedule s = parse(oneDay().lesson(MONDAY, 0, 0, "Физическая культура", "Т.Т.", "спортзал"));

        assertThat(s.entries()).singleElement().satisfies(e -> {
            assertThat(e.lessonType()).isEqualTo(LessonType.OTHER);
            assertThat(e.subject()).isEqualTo("Физическая культура");
        });
        assertThat(s.diagnostics().issues()).isEmpty();
    }

    @Test
    void subgroupWrittenAtTheEndOfTheSubjectIsReadAndRemovedFromIt() {
        ParsedSchedule s = parse(oneDay().lesson(MONDAY, 0, 0, "лаб. Технология бродильных производств- 2 п/г", "Т.Т.", "а.к"));

        assertThat(s.entries()).singleElement().satisfies(e -> {
            assertThat(e.subgroup()).isEqualTo(2);
            assertThat(e.subject()).isEqualTo("Технология бродильных производств");
        });
    }

    @Test
    void twoTeachersAndTwoRoomsStayOneLessonShowingBoth() {
        ParsedSchedule s = parse(oneDay().lesson(MONDAY, 0, 0, "лаб. Основы диетологии\nПищевые добавки",
                "Карлов Д.А.  Выродов Д.М.", "20б", "20з"));

        assertThat(s.entries()).singleElement().satisfies(e -> {
            assertThat(e.subject()).isEqualTo("Основы диетологии / Пищевые добавки");
            assertThat(e.teacherText()).isEqualTo("Карлов Д.А., Выродов Д.М.");
            assertThat(e.roomText()).isEqualTo("20б, 20з");
            assertThat(e.rawText()).contains("Основы диетологии").contains("Карлов Д.А.  Выродов Д.М.").contains("20з");
        });
    }

    @Test
    void sameRoomOnBothRowsIsNotRepeated() {
        ParsedSchedule s = parse(oneDay().lesson(MONDAY, 0, 0, "пр. А", "Т.Т.", "5-37", "5-37"));

        assertThat(s.entries().getFirst().roomText()).isEqualTo("5-37");
    }

    @Test
    void lessonWithoutTeacherOrRoomIsStillALesson() {
        ParsedSchedule s = parse(oneDay().lesson(MONDAY, 0, 0, "пр. А", null, null));

        assertThat(s.entries()).singleElement().satisfies(e -> {
            assertThat(e.teacherText()).isNull();
            assertThat(e.roomText()).isNull();
        });
    }

    @Test
    void teacherOrRoomWithoutSubjectIsASkippedLessonNotSilence() {
        ParsedSchedule s = parse(oneDay().lesson(MONDAY, 0, 0, "пр. А", "Т.Т.", "1").lesson(MONDAY, 1, 0, null, "Т.Т.", "1"));

        assertThat(s.entries()).hasSize(1);
        assertThat(s.diagnostics().skipped()).isEqualTo(1);
        assertThat(only(s, "LESSON_WITHOUT_SUBJECT").severity()).isEqualTo(IssueSeverity.ERROR);
    }

    // ---- dates -------------------------------------------------------------------------------------------

    @Test
    void theYearIsTheFirstOneOnOrAfterThePeriodStartSoJanuaryIsNextYear() {
        ParsedSchedule s = parse(ZaoFixture.withGroups("ЗАП-22")
                .day("ПТН \n25/12", "08.00-09.35").day("ПНД \n 4/01", "08.00-09.35")
                .lesson("ПТН \n25/12", 0, 0, "пр. А", "Т.Т.", "1").lesson("ПНД \n 4/01", 0, 0, "пр. Б", "Т.Т.", "1"));

        assertThat(s.entries()).extracting(ParsedEntry::date)
                .containsExactly(LocalDate.of(2026, 12, 25), LocalDate.of(2027, 1, 4));
    }

    @Test
    void weekdayThatDoesNotMatchTheDateRejectsTheWholeDayAndReportsItsLessons() {
        // 13/10/2026 is a Tuesday: a Monday label is a typo or a wrongly pinned semester
        ParsedSchedule s = parse(ZaoFixture.withGroups("ЗАП-22")
                .day("ПНД \n13/10", "08.00-09.35").day(TUESDAY, "08.00-09.35")
                .lesson("ПНД \n13/10", 0, 0, "пр. А", "Т.Т.", "1").lesson(TUESDAY, 0, 0, "пр. Б", "Т.Т.", "1"));

        assertThat(s.entries()).extracting(ParsedEntry::subject).containsExactly("Б");
        assertThat(only(s, "DAY_WEEKDAY_MISMATCH").severity()).isEqualTo(IssueSeverity.ERROR);
        assertThat(s.diagnostics().issues()).extracting(ParseIssue::code).contains("UNPLACED_TEXT");
        assertThat(s.diagnostics().skipped()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void wrongPeriodStartIsCaughtByTheWeekdayCheck() {
        // with a period starting in 2025 "ПНД 12/10" is a Sunday: every day is rejected, nothing is imported
        ZaoFixture f = ZaoFixture.withGroups("ЗАП-22").day(MONDAY, "08.00-09.35").lesson(MONDAY, 0, 0, "пр. А", "Т.Т.", "1");

        assertThatThrownBy(() -> parser.parse(f.stream(), new ParseContext(LocalDate.of(2025, 9, 1))))
                .isInstanceOf(ScheduleParseException.class).hasMessageContaining("No lessons")
                .satisfies(e -> assertThat(((ScheduleParseException) e).diagnostics().issues())
                        .extracting(ParseIssue::code).contains("DAY_WEEKDAY_MISMATCH"));
    }

    @Test
    void unknownDayLabelAndImpossibleDateAreReportedAndSkipped() {
        ParsedSchedule s = parse(ZaoFixture.withGroups("ЗАП-22")
                .day("Каникулы", "08.00-09.35").day("ВТР \n31/02", "08.00-09.35").day(MONDAY, "08.00-09.35")
                .lesson(MONDAY, 0, 0, "пр. А", "Т.Т.", "1"));

        assertThat(s.entries()).hasSize(1);
        assertThat(s.diagnostics().issues()).extracting(ParseIssue::code).contains("UNKNOWN_DAY", "INVALID_DATE");
    }

    @Test
    void withoutAPeriodThereIsNoYearAndTheParserSaysHowToFixIt() {
        assertThatThrownBy(() -> parser.parse(oneDay().lesson(MONDAY, 0, 0, "пр. А", "Т.Т.", "1").stream()))
                .isInstanceOf(ScheduleParseException.class)
                .hasMessageContaining("pin the source");
        assertThatThrownBy(() -> parser.parse(oneDay().stream(), ParseContext.unknown()))
                .isInstanceOf(ScheduleParseException.class).hasMessageContaining("academic period");
    }

    // ---- nothing is dropped silently ---------------------------------------------------------------------

    @Test
    void hiddenGroupIsIgnoredAndReported() {
        ParsedSchedule s = parse(oneDay().hideColumn("F").hideColumn("G").hideColumn("H")
                .lesson(MONDAY, 0, 0, "пр. А", "Т.Т.", "1").lesson(MONDAY, 0, 1, "пр. Б", "Т.Т.", "1"));

        assertThat(s.groups()).extracting(g -> g.code()).containsExactly("ЗАП-22");
        assertThat(s.entries()).hasSize(1);
        assertThat(only(s, "HIDDEN_GROUPS_IGNORED").message()).contains("ЗМО-22");
        assertThat(only(s, "HIDDEN_CELLS_IGNORED").message()).startsWith("3 non-empty cell(s)");
    }

    @Test
    void cellsOutsideEveryGroupAreCountedInOneWarning() {
        // a room left in column Z that belongs to no group (the real file has six such cells in column D)
        ParsedSchedule s = parse(oneDay().lesson(MONDAY, 0, 0, "пр. А", "Т.Т.", "1").put("Z2", "039").put("Z4", "028"));

        assertThat(s.entries()).hasSize(1);
        ParseIssue issue = only(s, "CELLS_OUTSIDE_GROUPS_IGNORED");
        assertThat(issue.severity()).isEqualTo(IssueSeverity.WARNING);
        assertThat(issue.message()).startsWith("2 non-empty cell(s)");
        assertThat(issue.location().position()).isEqualTo("Z2");
    }

    @Test
    void textInTheVisibleMiddleColumnOfAGroupIsASkippedLessonNotSilence() {
        // the middle column is normally hidden; here it is visible and holds text
        ParsedSchedule s = parse(oneDay().showColumn("D").lesson(MONDAY, 0, 0, "пр. А", "Т.Т.", "1").put("D2", "что-то лишнее"));

        assertThat(s.entries()).hasSize(1);
        assertThat(only(s, "UNPLACED_TEXT").severity()).isEqualTo(IssueSeverity.ERROR);
        assertThat(s.diagnostics().skipped()).isEqualTo(1);
    }

    @Test
    void slotWithUnreadableTimeIsReportedAndItsLessonsAreNotLost() {
        ParsedSchedule s = parse(ZaoFixture.withGroups("ЗАП-22").day(MONDAY, "08.00-09.35", "после обеда")
                .lesson(MONDAY, 0, 0, "пр. А", "Т.Т.", "1").lesson(MONDAY, 1, 0, "пр. Б", "Т.Т.", "1"));

        assertThat(s.entries()).extracting(ParsedEntry::subject).containsExactly("А");
        assertThat(only(s, "UNPARSEABLE_TIME").severity()).isEqualTo(IssueSeverity.WARNING);
        // subject, teacher and room of the unreadable slot: three cells reported as skipped
        assertThat(s.diagnostics().issues()).filteredOn(i -> i.code().equals("UNPLACED_TEXT")).hasSize(3);
        assertThat(s.diagnostics().skipped()).isEqualTo(3);
    }

    // ---- fatal structure ---------------------------------------------------------------------------------

    @Test
    void sheetWithoutHeaderIsRejected() {
        ZaoFixture f = ZaoFixture.withGroups("ЗАП-22").day(MONDAY, "08.00-09.35");
        f.put("A1", "что-то другое");

        assertThatThrownBy(() -> parse(f)).isInstanceOf(ScheduleParseException.class).hasMessageContaining("'Дни'");
    }

    @Test
    void workbookWithoutLessonsIsRejected() {
        assertThatThrownBy(() -> parse(oneDay())).isInstanceOf(ScheduleParseException.class).hasMessageContaining("No lessons");
    }

    @Test
    void notAnExcelFileIsRejectedWithAClearMessage() {
        assertThatThrownBy(() -> parser.parse(new java.io.ByteArrayInputStream("hello".getBytes()), AUTUMN_2026))
                .isInstanceOf(ScheduleParseException.class).hasMessageContaining("Excel");
    }
}
