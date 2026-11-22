package ru.vsuet.schedule.importer.parser.grid;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Admission year in a group code, turned into a course; codes as they appear in the autumn 2026 files. */
class GroupCodeYearTest {

    @ParameterizedTest(name = "{0} in {1} -> course {2}")
    @CsvSource({
            // two digits of the year, then the number of the group
            "У-261,   2026, 1", "М-260,   2026, 1", "Т-268,   2026, 1",
            "У-251,   2026, 2", "Ум-253,  2026, 2", "Хм-254,  2026, 2", "Мм-251,  2026, 2", "Тм-250,  2026, 2",
            "У-241,   2026, 3", "У-231,   2026, 4", "ЗАП-22,  2026, 5", "ПКД-239, 2026, 4", "ИСП-2510, 2026, 2",
            // one digit of the year, then the number of the group
            "ЭБ-61,   2026, 1", "Эмм-62,  2026, 1", "УБ-51,   2026, 2", "ТБ-41,   2026, 3", "ЭБ-31,   2026, 4", "УБ-21,   2026, 5",
            // the same codes the year before: the course moves with the academic year
            "У-251,   2025, 1", "УБ-11,   2025, 5", "ЭБ-51,   2025, 1",
            // the one-digit form repeats every ten years: the latest year not after the academic year wins
            "ЭБ-01,   2030, 1", "ЭБ-91,   2030, 2"})
    void courseFromTheCode(String code, int academicYearFrom, int expected) {
        assertThat(GroupCodeYear.courseOf(code, academicYearFrom)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"Магистры", "Ум", "У-1", "К-", "без цифр"})
    void codesWithoutAYearGiveNothing(String code) {
        assertThat(GroupCodeYear.courseOf(code, 2026)).isNull();
    }
}
