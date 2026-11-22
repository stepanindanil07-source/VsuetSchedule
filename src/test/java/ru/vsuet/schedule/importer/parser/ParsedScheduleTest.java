package ru.vsuet.schedule.importer.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.vsuet.schedule.academic.domain.AcademicTerm;
import ru.vsuet.schedule.schedule.domain.LessonType;
import ru.vsuet.schedule.common.WeekType;

class ParsedScheduleTest {

    private static final ParsedPeriod PERIOD = new ParsedPeriod(2025, 2026, AcademicTerm.SPRING);
    private static final ParseDiagnostics DIAGNOSTICS = new DiagnosticsCollector().build("1");

    private static ParsedEntry entryFor(String groupCode) {
        return new ParsedEntry(groupCode, DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(9, 35),
                WeekType.NUMERATOR, null, "Физика", LessonType.LECTURE, null, null, "л.Физика",
                new SourceLocation("1 курс", "C8"));
    }

    @Test
    void acceptsEntriesOfKnownGroups() {
        ParsedSchedule schedule = new ParsedSchedule("fuits-xlsx", PERIOD,
                List.of(new ParsedGroup("У-251")), List.of(entryFor("У-251")), DIAGNOSTICS);

        assertThat(schedule.entries()).hasSize(1);
    }

    @Test
    void rejectsEntryOfUnknownGroup() {
        assertThatThrownBy(() -> new ParsedSchedule("fuits-xlsx", PERIOD,
                List.of(new ParsedGroup("У-251")), List.of(entryFor("У-999")), DIAGNOSTICS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("У-999");
    }

    @Test
    void rejectsDuplicateGroups() {
        assertThatThrownBy(() -> new ParsedSchedule("fuits-xlsx", PERIOD,
                List.of(new ParsedGroup("У-251"), new ParsedGroup("У-251")), List.of(), DIAGNOSTICS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void periodMustSpanTwoConsecutiveYears() {
        assertThatThrownBy(() -> new ParsedPeriod(2025, 2027, AcademicTerm.SPRING))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listsAreDefensivelyCopied() {
        var groups = new java.util.ArrayList<>(List.of(new ParsedGroup("У-251")));
        ParsedSchedule schedule = new ParsedSchedule("fuits-xlsx", PERIOD, groups, List.of(), DIAGNOSTICS);

        groups.add(new ParsedGroup("У-252"));

        assertThat(schedule.groups()).hasSize(1);
    }
}
