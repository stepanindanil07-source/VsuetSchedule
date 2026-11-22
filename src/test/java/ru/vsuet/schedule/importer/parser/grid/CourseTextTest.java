package ru.vsuet.schedule.importer.parser.grid;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** How course numbers are read from header texts and sheet names of the real files. */
class CourseTextTest {

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
            "2 КУРС                                                   | [2]",
            "1 курс                                                   | [1]",
            "4  курс                                                  | [4]",
            "учебных занятий  3 курса  факультета                     | [3]",
            "РАСПИСАНИЕ  УЧЕБНЫХ  ЗАНЯТИЙ   1  КУРСА   ФСПО   НА  ОСЕННИЙ  СЕМЕСТР 2026 - 2027 учебного года | [1]",
            "учебных занятий  1 и 2  курсов  Технологического факультета | [1, 2]",
            "учебных занятий  1, 2 и 5 курсов  факультета             | [1, 2, 5]",
            "5 курс + магистры                                        | [5]",
            "2-й курс                                                 | [2]",
            "магистры                                                 | []",
            "10 МЕСЯЦЕВ                                               | []",
            "ИСП-215 курсовая работа                                  | []",
            "осеннего семестра  2026-2027 учебного года               | []",
    })
    void readsCourseNumbers(String text, String expected) {
        assertThat(GridSheetParser.coursesIn(text)).hasToString(expected);
    }
}
