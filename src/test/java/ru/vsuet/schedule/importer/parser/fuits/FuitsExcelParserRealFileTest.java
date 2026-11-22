package ru.vsuet.schedule.importer.parser.fuits;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ru.vsuet.schedule.academic.domain.AcademicTerm;
import ru.vsuet.schedule.importer.parser.ParsedEntry;
import ru.vsuet.schedule.importer.parser.ParsedGroup;
import ru.vsuet.schedule.importer.parser.ParsedSchedule;
import ru.vsuet.schedule.schedule.domain.LessonType;
import ru.vsuet.schedule.common.WeekType;

/** Runs the parser on the real uits.xlsx (spring 2025-2026) and pins down known cells. */
class FuitsExcelParserRealFileTest {

    private static ParsedSchedule schedule;

    @BeforeAll
    static void parseRealFile() throws Exception {
        try (InputStream in = FuitsExcelParserRealFileTest.class.getResourceAsStream("/fuits/uits.xlsx")) {
            schedule = new FuitsExcelParser().parse(in);
        }
    }

    private static ParsedEntry only(String group, Predicate<ParsedEntry> filter) {
        List<ParsedEntry> found = schedule.entries().stream()
                .filter(e -> e.groupCode().equals(group)).filter(filter).toList();
        assertThat(found).as("entries of %s matching filter", group).hasSize(1);
        return found.getFirst();
    }

    private static Predicate<ParsedEntry> at(String sheet, String range) {
        return e -> e.location().container().equals(sheet) && e.location().position().equals(range);
    }

    @Test
    void readsPeriodFromTheSheetsThatHaveIt() {
        assertThat(schedule.period().yearFrom()).isEqualTo(2025);
        assertThat(schedule.period().yearTo()).isEqualTo(2026);
        assertThat(schedule.period().term()).isEqualTo(AcademicTerm.SPRING);
    }

    @Test
    void findsAllGroupsOfAllFiveSheets() {
        assertThat(schedule.groups()).extracting(ParsedGroup::code).containsExactly(
                "У-251", "У-252", "У-253", "У-254", "У-255", "У-256", "У-257", "У-258", "УБ-51", "УБ-52",
                "У-241", "У-242", "У-243", "У-244", "У-246", "У-247", "УБ-41", "УБ-42",
                "У-231", "У-232", "У-233", "У-234", "У-235", "У-236", "УБ-31", "УБ-32",
                "У-220", "У-221", "У-222", "У-223", "У-224", "У-225", "У-226", "УБ-21", "УБ-22", "УБ-23",
                "УБ-11", "УБ-12", "Ум-251", "Ум-252", "Ум-253", "Ум-254");
    }

    @Test
    void everyNonEmptyLessonCellBecomesAnEntry() {
        // 1161 non-empty lesson cells counted independently with openpyxl while studying the file
        assertThat(schedule.diagnostics().skipped()).isZero();
        assertThat(schedule.diagnostics().recognized()).isEqualTo(1161);
        assertThat(schedule.entries()).hasSize(1161);
        assertThat(schedule.diagnostics().errorCount()).isZero();
    }

    @Test
    void doesNotReportWarningsOnTheRealFile() {
        assertThat(schedule.diagnostics().issues()).isEmpty();
    }

    @Test
    void wholeGroupEveryWeekLesson() {
        // C10:C11 = merged 2 rows, group У-251 has a single column
        ParsedEntry e = only("У-251", at("1 курс", "C10:C11"));

        assertThat(e.dayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(e.startTime()).isEqualTo(LocalTime.of(9, 45));
        assertThat(e.endTime()).isEqualTo(LocalTime.of(11, 20));
        assertThat(e.weekType()).isEqualTo(WeekType.EVERY_WEEK);
        assertThat(e.subgroup()).isNull();
        assertThat(e.lessonType()).isEqualTo(LessonType.PRACTICE);
        assertThat(e.subject()).isEqualTo("История России");
        assertThat(e.teacherText()).isEqualTo("Гребенщиков А.Е.");
        assertThat(e.roomText()).isEqualTo("46");
    }

    @Test
    void topRowIsNumeratorAndBottomRowIsDenominator() {
        ParsedEntry numerator = only("У-251", at("1 курс", "C12"));
        ParsedEntry denominator = only("У-251", at("1 курс", "C13"));

        assertThat(numerator.weekType()).isEqualTo(WeekType.NUMERATOR);
        assertThat(numerator.subject()).isEqualTo("Программирование и основы алгоритмизации");
        assertThat(denominator.weekType()).isEqualTo(WeekType.DENOMINATOR);
        assertThat(denominator.subject()).isEqualTo("Введение в профессиональную деятельность");
        assertThat(numerator.startTime()).isEqualTo(denominator.startTime());
    }

    @Test
    void lessonMergedAcrossTheWholeTwoColumnGroupBelongsToEverySubgroup() {
        // D12:E13 = 2x2 merge over group У-252 (columns D:E)
        ParsedEntry e = only("У-252", at("1 курс", "D12:E13"));

        assertThat(e.subgroup()).isNull();
        assertThat(e.weekType()).isEqualTo(WeekType.EVERY_WEEK);
        assertThat(e.subject()).isEqualTo("Иностранный язык");
        // two teachers and two rooms in one cell are kept together
        assertThat(e.teacherText()).isEqualTo("Мирошниченко Е.Н., Павлова С.В.");
        assertThat(e.roomText()).isEqualTo("21, 11и");
    }

    @Test
    void singleCellInRightColumnIsSecondSubgroupInOneWeek() {
        // 2 курс: I12 (top row, right column of У-244) "Моделирование систем- 2 п/г"
        ParsedEntry second = only("У-244", at("2 курс", "I12"));
        ParsedEntry first = only("У-244", at("2 курс", "H13"));

        assertThat(second.subgroup()).isEqualTo(2);
        assertThat(second.weekType()).isEqualTo(WeekType.NUMERATOR);
        assertThat(first.subgroup()).isEqualTo(1);
        assertThat(first.weekType()).isEqualTo(WeekType.DENOMINATOR);
    }

    @Test
    void grandHallLessonKeepsItsRoomAndHasOtherType() {
        ParsedEntry e = only("У-252", at("1 курс", "D30:E31"));

        assertThat(e.lessonType()).isEqualTo(LessonType.OTHER);
        assertThat(e.roomText()).isEqualTo("БАЗ");
        assertThat(e.teacherText()).isNull();
    }

    @Test
    void masterGroupsOnSheetWithoutSemesterTextAreStillParsed() {
        assertThat(schedule.entries()).anyMatch(e -> e.groupCode().equals("Ум-251"));
        assertThat(schedule.entries()).anyMatch(e -> e.groupCode().equals("УБ-11"));
    }

    @Test
    void everyEntryKeepsRawTextAndSource() {
        assertThat(schedule.entries()).allSatisfy(e -> {
            assertThat(e.rawText()).isNotBlank();
            assertThat(e.location().container()).isNotBlank();
        });
    }

    @Test
    void entriesPerSheetAreDistributedAcrossAllSixDays() {
        Map<DayOfWeek, Long> perDay = schedule.entries().stream()
                .collect(Collectors.groupingBy(ParsedEntry::dayOfWeek, Collectors.counting()));

        assertThat(perDay).containsOnlyKeys(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY);
    }
}
