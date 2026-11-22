package ru.vsuet.schedule.importer.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import ru.vsuet.schedule.schedule.domain.LessonType;
import ru.vsuet.schedule.common.WeekType;

class ParsedEntryTest {

    private static final SourceLocation LOCATION = new SourceLocation("1 курс", "C10:C11");

    private static ParsedEntry entry(LocalTime start, LocalTime end, Integer subgroup, String subject, String teacher, String room) {
        return new ParsedEntry("У-251", DayOfWeek.MONDAY, start, end, WeekType.EVERY_WEEK, subgroup,
                subject, LessonType.PRACTICE, teacher, room, "пр.История России\nГребенщиков А.Е.   46", LOCATION);
    }

    @Test
    void acceptsWholeGroupLessonWithoutTeacherAndRoom() {
        ParsedEntry e = entry(LocalTime.of(9, 45), LocalTime.of(11, 20), null, "Общая физическая подготовка", null, null);

        assertThat(e.subgroup()).isNull();
        assertThat(e.teacherText()).isNull();
        assertThat(e.roomText()).isNull();
    }

    @Test
    void acceptsNumberedSubgroup() {
        ParsedEntry e = entry(LocalTime.of(9, 45), LocalTime.of(11, 20), 3, "Физика", "Леньшин А.С.", "55");

        assertThat(e.subgroup()).isEqualTo(3);
    }

    @Test
    void rejectsStartNotBeforeEnd() {
        assertThatThrownBy(() -> entry(LocalTime.of(11, 20), LocalTime.of(11, 20), null, "Физика", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startTime");
    }

    @Test
    void rejectsZeroSubgroup() {
        assertThatThrownBy(() -> entry(LocalTime.of(9, 45), LocalTime.of(11, 20), 0, "Физика", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subgroup");
    }

    @Test
    void rejectsBlankSubject() {
        assertThatThrownBy(() -> entry(LocalTime.of(9, 45), LocalTime.of(11, 20), null, "  ", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subject");
    }

    private static ParsedEntry dated(DayOfWeek day, WeekType week, LocalDate date) {
        return new ParsedEntry("ЗАП-22", day, LocalTime.of(8, 0), LocalTime.of(9, 35), week, null, "Физика",
                LessonType.EXAM, null, null, "экз. Физика", LOCATION, date);
    }

    @Test
    void weeklyLessonHasNoDate() {
        assertThat(entry(LocalTime.of(9, 45), LocalTime.of(11, 20), null, "Физика", null, null).date()).isNull();
    }

    @Test
    void acceptsOneOffLessonWhoseDateAgreesWithItsDay() {
        ParsedEntry e = dated(DayOfWeek.MONDAY, WeekType.EVERY_WEEK, LocalDate.of(2026, 10, 12));

        assertThat(e.date()).isEqualTo(LocalDate.of(2026, 10, 12));
    }

    @Test
    void rejectsDateThatIsAnotherDayOfTheWeek() {
        assertThatThrownBy(() -> dated(DayOfWeek.TUESDAY, WeekType.EVERY_WEEK, LocalDate.of(2026, 10, 12)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2026-10-12");
    }

    @Test
    void rejectsNumeratorOrDenominatorOnADatedLesson() {
        assertThatThrownBy(() -> dated(DayOfWeek.MONDAY, WeekType.NUMERATOR, LocalDate.of(2026, 10, 12)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("numerator");
    }
}
