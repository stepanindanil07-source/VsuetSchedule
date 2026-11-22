package ru.vsuet.schedule.importer.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.vsuet.schedule.common.Hashes;
import ru.vsuet.schedule.common.WeekType;
import ru.vsuet.schedule.importer.parser.ParsedEntry;
import ru.vsuet.schedule.importer.parser.SourceLocation;
import ru.vsuet.schedule.schedule.domain.LessonType;

class GroupContentHasherTest {

    private static ParsedEntry weekly() {
        return new ParsedEntry("Г-1", DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(9, 35), WeekType.NUMERATOR, 2,
                "Физика", LessonType.LAB, "Иванов И.И.", "12", "raw", new SourceLocation("1 курс", "C3"));
    }

    private static ParsedEntry onDate(LocalDate date) {
        return new ParsedEntry("Г-1", date.getDayOfWeek(), LocalTime.of(8, 0), LocalTime.of(9, 35), WeekType.EVERY_WEEK, null,
                "Физика", LessonType.EXAM, "Иванов И.И.", "12", "raw", new SourceLocation("Список 8", "C3:E4"), date);
    }

    @Test
    void hashOfWeeklyLessonsIsTheOneComputedBeforeDatesExisted() {
        // the exact line format of the weekly-only era; changing it would bump every group's version on deploy
        char sep = '\u001f';
        String line = String.join(String.valueOf(sep), "1", "08:00", "09:35", "NUMERATOR", "2", "Физика", "LAB", "Иванов И.И.", "12");

        assertThat(GroupContentHasher.hash(7L, List.of(weekly()))).isEqualTo(Hashes.sha256Hex("7\n" + line));
    }

    @Test
    void sameLessonOnAnotherDateIsAnotherLesson() {
        assertThat(GroupContentHasher.hash(1L, List.of(onDate(LocalDate.of(2026, 10, 12)))))
                .isNotEqualTo(GroupContentHasher.hash(1L, List.of(onDate(LocalDate.of(2026, 10, 19)))));
    }

    @Test
    void orderOfLessonsDoesNotMatter() {
        assertThat(GroupContentHasher.hash(1L, List.of(weekly(), onDate(LocalDate.of(2026, 10, 12)))))
                .isEqualTo(GroupContentHasher.hash(1L, List.of(onDate(LocalDate.of(2026, 10, 12)), weekly())));
    }

    @Test
    void aDatedLessonDiffersFromTheSameLessonWithoutADate() {
        ParsedEntry dated = onDate(LocalDate.of(2026, 10, 12));
        ParsedEntry weeklyTwin = new ParsedEntry("Г-1", DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(9, 35),
                WeekType.EVERY_WEEK, null, "Физика", LessonType.EXAM, "Иванов И.И.", "12", "raw", dated.location());

        assertThat(GroupContentHasher.hash(1L, List.of(dated))).isNotEqualTo(GroupContentHasher.hash(1L, List.of(weeklyTwin)));
    }
}
