package ru.vsuet.schedule.importer.parser.fuits;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.vsuet.schedule.importer.parser.grid.LessonTextParser;
import ru.vsuet.schedule.importer.parser.grid.LessonTextParser.CellContent;
import ru.vsuet.schedule.importer.parser.grid.LessonTextParser.Rejected;
import ru.vsuet.schedule.importer.parser.grid.LessonTextParser.Result;
import ru.vsuet.schedule.schedule.domain.LessonType;

/** Every example below is a real cell text taken from uits.xlsx (spring 2025-2026). */
class CellTextParserTest {

    private final CellTextParser parser = new CellTextParser();

    private CellContent parsed(String text) {
        Result result = parser.parse(text);
        assertThat(result).as("result for %s", text).isInstanceOf(CellContent.class);
        return (CellContent) result;
    }

    @Nested
    class Standard {

        @ParameterizedTest
        @CsvSource(delimiter = '|', textBlock = """
                пр.История России\\nГребенщиков А.Е.   46            | PRACTICE | История России | Гребенщиков А.Е. | 46
                л.Химия\\nКозадёрова О.А.   020                      | LECTURE  | Химия          | Козадёрова О.А.  | 020
                лаб.Физика\\nЛеньшин А.С.   55                       | LAB      | Физика         | Леньшин А.С.     | 55
                """)
        void splitsTypeSubjectTeacherAndRoom(String text, LessonType type, String subject, String teacher, String room) {
            CellContent c = parsed(text.replace("\\n", "\n"));

            assertThat(c.lessonType()).isEqualTo(type);
            assertThat(c.subject()).isEqualTo(subject);
            assertThat(c.teacherText()).isEqualTo(teacher);
            assertThat(c.roomText()).isEqualTo(room);
            assertThat(c.warnings()).isEmpty();
        }

        @Test
        void keepsSubgroupMarkerInsideSubject() {
            CellContent c = parsed("пр.Моделирование систем- 2 п/г\nЛитвинов Д.А.   336а");

            assertThat(c.subject()).isEqualTo("Моделирование систем- 2 п/г");
            assertThat(c.roomText()).isEqualTo("336а");
        }

        @Test
        void keepsTruncatedSubjectAsWritten() {
            assertThat(parsed("пр.Вычислительные системы...\nХромых Е.А.   337").subject())
                    .isEqualTo("Вычислительные системы...");
        }

        @Test
        void subjectMayContainDotsAndParentheses() {
            assertThat(parsed("пр.Метод. и технология проект. ИС\nБородина Е.А.   336а").subject())
                    .isEqualTo("Метод. и технология проект. ИС");
            assertThat(parsed("л.Экономика фирмы (организации)\nИванов И.И.   1").subject())
                    .isEqualTo("Экономика фирмы (организации)");
        }

        @Test
        void collapsesRepeatedSpacesAndLineEndings() {
            CellContent c = parsed("пр.История   России\r\nГребенщиков А.Е.   46  ");

            assertThat(c.subject()).isEqualTo("История России");
            assertThat(c.teacherText()).isEqualTo("Гребенщиков А.Е.");
            assertThat(c.roomText()).isEqualTo("46");
        }
    }

    @Nested
    class Rooms {

        @ParameterizedTest
        @CsvSource(delimiter = '|', textBlock = """
                пр.Компьютерная и инженерная графика\\nНосова Е.А.   6-33 | 6-33
                лаб.Технические измерения и приборы\\nСолощенко О.Е.   а.к | а.к
                пр.Иностранный язык\\nПавлова С.В.   11и | 11и
                """)
        void keepsRoomNotationsAsWritten(String text, String room) {
            assertThat(parsed(text.replace("\\n", "\n")).roomText()).isEqualTo(room);
        }

        @Test
        void keepsTrailingZMarkerNextToTheRoom() {
            CellContent c = parsed("лаб.Экология\nПопова Л.В.   6-32   Z");

            assertThat(c.teacherText()).isEqualTo("Попова Л.В.");
            assertThat(c.roomText()).isEqualTo("6-32, Z");
        }

        @Test
        void roomOnlyLineHasNoTeacher() {
            CellContent c = parsed("лаб.Продукты питания животного происх.\n   236");

            assertThat(c.teacherText()).isNull();
            assertThat(c.roomText()).isEqualTo("236");
        }

        @Test
        void grandHallMarkerIsARoom() {
            CellContent c = parsed("Курс \"О СМЫСЛАХ И ЦЕННОСТЯХ\"\n   БАЗ");

            assertThat(c.lessonType()).isEqualTo(LessonType.OTHER);
            assertThat(c.subject()).isEqualTo("Курс \"О СМЫСЛАХ И ЦЕННОСТЯХ\"");
            assertThat(c.teacherText()).isNull();
            assertThat(c.roomText()).isEqualTo("БАЗ");
        }

        @Test
        void unknownDormitoryStyleRoomIsKeptWithoutGuessing() {
            CellContent c = parsed("пр.Русский язык и культура речи\nобщ.2");

            assertThat(c.roomText()).isEqualTo("общ.2");
            assertThat(c.teacherText()).isNull();
        }
    }

    @Nested
    class SeveralTeachers {

        @Test
        void foreignLanguageCellKeepsBothTeachersAndBothRooms() {
            CellContent c = parsed("пр.Иностранный язык\nМирошниченко Е.Н.   21           Павлова С.В.   11и");

            assertThat(c.subject()).isEqualTo("Иностранный язык");
            assertThat(c.teacherText()).isEqualTo("Мирошниченко Е.Н., Павлова С.В.");
            assertThat(c.roomText()).isEqualTo("21, 11и");
            assertThat(c.warnings()).isEmpty();
        }

        @Test
        void longSubjectWithTwoTeachers() {
            CellContent c = parsed("пр.Иностранный язык профессионального и делового общения\n"
                    + "Прибыткова О.В.   2и          Ларина Л.И.   3и");

            assertThat(c.teacherText()).isEqualTo("Прибыткова О.В., Ларина Л.И.");
            assertThat(c.roomText()).isEqualTo("2и, 3и");
        }
    }

    @Nested
    class NoTypeMarker {

        @Test
        void singleLineWithoutMarkerIsOtherWithoutTeacherAndRoom() {
            CellContent c = parsed("Общая физическая подготовка");

            assertThat(c.lessonType()).isEqualTo(LessonType.OTHER);
            assertThat(c.subject()).isEqualTo("Общая физическая подготовка");
            assertThat(c.teacherText()).isNull();
            assertThat(c.roomText()).isNull();
            assertThat(c.warnings()).isEmpty();
        }

        @Test
        void trailingDotOfSubjectIsKept() {
            assertThat(parsed("Общая физическая подготовка.").subject()).isEqualTo("Общая физическая подготовка.");
        }

        @Test
        void abbreviatedCapitalisedWordIsNotMistakenForAMarker() {
            CellContent c = parsed("пр.Прогр.-апп. средства ЗИ\nЕвдокимова С.А.   332а");

            assertThat(c.subject()).isEqualTo("Прогр.-апп. средства ЗИ");
            assertThat(c.lessonType()).isEqualTo(LessonType.PRACTICE);
        }
    }

    @Nested
    class Unexpected {

        @Test
        void unknownLowercaseMarkerKeepsSubjectAndWarns() {
            CellContent c = parsed("сем.Физика\nИванов И.И.   12");

            assertThat(c.lessonType()).isEqualTo(LessonType.OTHER);
            assertThat(c.subject()).isEqualTo("Физика");
            assertThat(c.warnings()).extracting(LessonTextParser.Warning::code).containsExactly("UNKNOWN_LESSON_MARKER");
            assertThat(c.warnings().getFirst().message()).contains("сем");
        }

        @Test
        void unrecognisedPersonFormatIsKeptAndWarned() {
            CellContent c = parsed("пр.Физика\nИванов И.   12");

            assertThat(c.teacherText()).isEqualTo("Иванов И.");
            assertThat(c.roomText()).isEqualTo("12");
            assertThat(c.warnings()).extracting(LessonTextParser.Warning::code).containsExactly("UNRECOGNIZED_DETAIL");
        }

        @Test
        void singleSpaceBetweenTeacherAndRoomStillSplits() {
            CellContent c = parsed("пр.Физика\nИванов И.И. 12");

            assertThat(c.teacherText()).isEqualTo("Иванов И.И.");
            assertThat(c.roomText()).isEqualTo("12");
        }

        @Test
        void extraLinesAreNotLost() {
            CellContent c = parsed("пр.Физика\nИванов И.И.   12\nПетров П.П.   13");

            assertThat(c.teacherText()).isEqualTo("Иванов И.И., Петров П.П.");
            assertThat(c.roomText()).isEqualTo("12, 13");
        }

        @Test
        void markerWithoutSubjectIsRejected() {
            assertRejected("пр.", "EMPTY_SUBJECT");
            assertRejected("пр.\nИванов И.И.   12", "EMPTY_SUBJECT");
        }

        @Test
        void blankTextIsRejected() {
            assertRejected("", "EMPTY_TEXT");
            assertRejected("   \n ", "EMPTY_TEXT");
            assertRejected(null, "EMPTY_TEXT");
        }

        @Test
        void zeroPlaceholderIsRejectedSoTheCallerCanTreatItAsEmpty() {
            assertRejected("0", "ZERO_PLACEHOLDER");
            assertRejected(" 0 ", "ZERO_PLACEHOLDER");
        }

        private void assertRejected(String text, String code) {
            Result result = parser.parse(text);

            assertThat(result).isInstanceOfSatisfying(Rejected.class, r -> assertThat(r.code()).isEqualTo(code));
        }
    }

    @Nested
    class TeacherSpellingVariants {

        @ParameterizedTest
        @CsvSource(delimiter = '|', textBlock = """
                л.Автоматизация\\nАлексеев Д.В..   134      | Алексеев Д.В..    | 134
                пр.Иностранный язык\\nКозыренко Е.В,    11и | Козыренко Е.В     | 11и
                пр.Иностранный язык\\nХрячкова Л.А   16     | Хрячкова Л.А      | 16
                пр.Управление\\nЧекмарева_Пол М.С.   116    | Чекмарева_Пол М.С. | 116
                """)
        void recognisesTeacherWithoutCorrectingTheSpelling(String text, String teacher, String room) {
            CellContent c = parsed(text.replace("\\n", "\n"));

            assertThat(c.teacherText()).isEqualTo(teacher);
            assertThat(c.roomText()).isEqualTo(room);
            assertThat(c.warnings()).isEmpty();
        }
    }
}
