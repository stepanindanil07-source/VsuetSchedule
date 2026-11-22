package ru.vsuet.schedule.importer.parser.spo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.vsuet.schedule.importer.parser.grid.LessonTextParser.CellContent;
import ru.vsuet.schedule.importer.parser.grid.LessonTextParser.Rejected;
import ru.vsuet.schedule.importer.parser.grid.LessonTextParser.Result;
import ru.vsuet.schedule.importer.parser.grid.LessonTextParser.Warning;
import ru.vsuet.schedule.schedule.domain.LessonType;

/** Every example is a real cell text from 1_kurs.xls / 2-3-4_kurs.xls (autumn 2026-2027), spacing included. */
class SpoCellTextParserTest {

    private final SpoCellTextParser parser = new SpoCellTextParser();

    private CellContent parsed(String text) {
        Result result = parser.parse(text);
        assertThat(result).as("result for %s", text).isInstanceOf(CellContent.class);
        return (CellContent) result;
    }

    @Nested
    class TypicalLessons {

        @Test
        void subjectMarkerTeacherAndRoom() {
            CellContent c = parsed("ХИМИЯ    (практические занятия)                                           Воронцов И.Н.,      а. 33");

            assertThat(c.subject()).isEqualTo("ХИМИЯ");
            assertThat(c.lessonType()).isEqualTo(LessonType.PRACTICE);
            assertThat(c.teacherText()).isEqualTo("Воронцов И.Н.");
            assertThat(c.roomText()).isEqualTo("33");
            assertThat(c.subgroup()).isNull();
            assertThat(c.warnings()).isEmpty();
        }

        @Test
        void lessonWithoutAMarkerHasTypeOther() {
            CellContent c = parsed("ИНФОРМАТИКА                               Миронов А.Н.,     а. 19");

            assertThat(c.lessonType()).isEqualTo(LessonType.OTHER);
            assertThat(c.subject()).isEqualTo("ИНФОРМАТИКА");
            assertThat(c.roomText()).isEqualTo("19");
        }

        @Test
        void wordsOfTheSubjectSeparatedByDoubleSpacesAreCollapsed() {
            CellContent c = parsed("ИНОСТРАННЫЙ  ЯЗЫК  В  ПРОФЕССИОНАЛЬНОЙ  ДЕЯТЕЛЬНОСТИ       Епишова И.Н.,      а.   Общ. 2");

            assertThat(c.subject()).isEqualTo("ИНОСТРАННЫЙ ЯЗЫК В ПРОФЕССИОНАЛЬНОЙ ДЕЯТЕЛЬНОСТИ");
            assertThat(c.teacherText()).isEqualTo("Епишова И.Н.");
            assertThat(c.roomText()).isEqualTo("Общ. 2");
        }

        @Test
        void subjectWithDotsAndLowercaseWords() {
            CellContent c = parsed("МДК.01.02 Технология производства хлеба и хлебобулочных изделий     Шапкарина А.И.,   (лаб)"
                    + "                    1 под.,                                а. 18 а");

            assertThat(c.subject()).isEqualTo("МДК.01.02 Технология производства хлеба и хлебобулочных изделий");
            assertThat(c.lessonType()).isEqualTo(LessonType.LAB);
            assertThat(c.subgroup()).isEqualTo(1);
            assertThat(c.teacherText()).isEqualTo("Шапкарина А.И.");
            assertThat(c.roomText()).isEqualTo("18 а");
        }
    }

    @Nested
    class LessonTypeMarkers {

        @Test
        void recognisesTheSpellingsUsedInTheFiles() {
            assertThat(parsed("ФИЗИКА (лаб.) Иванов И.И., а. 1").lessonType()).isEqualTo(LessonType.LAB);
            assertThat(parsed("ФИЗИКА (лаб) Иванов И.И., а. 1").lessonType()).isEqualTo(LessonType.LAB);
            assertThat(parsed("ФИЗИКА (лабораторные занятия) Иванов И.И., а. 1").lessonType()).isEqualTo(LessonType.LAB);
            assertThat(parsed("ФИЗИКА (лабораторные работы) Иванов И.И., а. 1").lessonType()).isEqualTo(LessonType.LAB);
            assertThat(parsed("ХИМИЯ (лоаб.) Иванов И.И., а. 1").lessonType()).isEqualTo(LessonType.LAB);       // typo in the source
            assertThat(parsed("ФИЗИКА (практические занятия) Иванов И.И., а. 1").lessonType()).isEqualTo(LessonType.PRACTICE);
            assertThat(parsed("ФИЗИКА (практическипе занятия) Иванов И.И., а. 1").lessonType()).isEqualTo(LessonType.PRACTICE);
            assertThat(parsed("ФИЗИКА (теория) Иванов И.И., а. 1").lessonType()).isEqualTo(LessonType.LECTURE);
            assertThat(parsed("ФИЗИКА (Теория) Иванов И.И., а. 1").lessonType()).isEqualTo(LessonType.LECTURE);
            assertThat(parsed("ФИЗИКА (теория.) Иванов И.И., а. 1").lessonType()).isEqualTo(LessonType.LECTURE);
            assertThat(parsed("ТЕХНОЛОГИЯ БЛЮД РУССКОЙ КУХНИ (лек), Минаева С.В., а. 26").lessonType()).isEqualTo(LessonType.LECTURE);
        }

        @Test
        void aMarkerNamingTwoTypesIsOtherWithAWarning() {
            CellContent c = parsed("ФИЗИКА (практические, лабораторные занятия) Иванов И.И., а. 1");

            assertThat(c.lessonType()).isEqualTo(LessonType.OTHER);
            assertThat(c.subject()).isEqualTo("ФИЗИКА");
            assertThat(c.warnings()).extracting(Warning::code).containsExactly("AMBIGUOUS_LESSON_TYPE");
        }

        @Test
        void unknownParenthesesStayInTheSubject() {
            CellContent c = parsed("Индивидуальный проект (Основы проектной деятельности)     Z");

            assertThat(c.subject()).startsWith("Индивидуальный проект (Основы проектной деятельности)");
            assertThat(c.lessonType()).isEqualTo(LessonType.OTHER);
        }

        @Test
        void consultationCellKeepsItsWholeText() {
            CellContent c = parsed("Консультации по учебным дисциплинам (в соответствии с графиком)");

            assertThat(c.subject()).isEqualTo("Консультации по учебным дисциплинам (в соответствии с графиком)");
            assertThat(c.teacherText()).isNull();
            assertThat(c.roomText()).isNull();
        }
    }

    @Nested
    class Subgroups {

        @Test
        void subgroupIsTakenFromTheTextAndRemovedFromTheSubject() {
            CellContent c = parsed("ХИМИЯ                                  (лоаб.)   1 под.                          Черноусова О.В.,       а. 25");

            assertThat(c.subgroup()).isEqualTo(1);
            assertThat(c.subject()).isEqualTo("ХИМИЯ");
            assertThat(c.lessonType()).isEqualTo(LessonType.LAB);
        }

        @Test
        void spellingVariantsOfTheSubgroupMarker() {
            assertThat(parsed("ОСНОВЫ ПРОЕКТИРОНИЯ ЦИФРОВОЙ ТЕХНИКИ      1 под      (лаб.)     Санин А.И.,     а.  19").subgroup()).isEqualTo(1);
            assertThat(parsed("ПМ.05.01 ТЕХНОЛОГИЯ (лабораторные работы) 2 Под. Иванчиков Д.С., а. 5").subgroup()).isEqualTo(2);
            assertThat(parsed("ПМ.01 (лаб) Курило Н.Г., 1 подг. а. 25").subgroup()).isEqualTo(1);
        }

        @Test
        void subgroupAfterTheSubjectBeforeTheTeacher() {
            CellContent c = parsed("ОСНОВЫ  АНАЛИТИЧЕСКОЙ  ХИМИИ   (лаб.)                  1 под.        Черногусова О.В.       ауд.   32");

            assertThat(c.subject()).isEqualTo("ОСНОВЫ АНАЛИТИЧЕСКОЙ ХИМИИ");
            assertThat(c.subgroup()).isEqualTo(1);
            assertThat(c.roomText()).isEqualTo("32");
        }
    }

    @Nested
    class TeachersAndRooms {

        @Test
        void severalTeachers() {
            CellContent c = parsed("ФИЗИЧЕСКАЯ КУЛЬТУРА   спорт.зал,тренажерный зал, стадион   Калугин С.Л.,   Платонов Э.В.,   Тихонов М.С.");

            assertThat(c.subject()).isEqualTo("ФИЗИЧЕСКАЯ КУЛЬТУРА");
            assertThat(c.teacherText()).isEqualTo("Калугин С.Л., Платонов Э.В., Тихонов М.С.");
            assertThat(c.roomText()).isEqualTo("спорт.зал,тренажерный зал, стадион");
        }

        @Test
        void teacherWithOneInitialAndTrailingDots() {
            assertThat(parsed("ИНОСТРАННЫЙ ЯЗЫК  Иванчиков Д.,   (лаб)  2 под.,   а. 306 ВГУИТ").teacherText()).isEqualTo("Иванчиков Д.");
            assertThat(parsed("БИОЛОГИЯ (практические занятия) Абрамова Е.., а. ВГУИТ").teacherText()).isEqualTo("Абрамова Е.");
        }

        @Test
        void roomMarkerSpellings() {
            assertThat(parsed("БЖД  Жесткова Т.В.        Ауд. 19").roomText()).isEqualTo("19");
            assertThat(parsed("ГЕОГРАФИЯ Бажухина Е.И., а 26.").roomText()).isEqualTo("26");
            assertThat(parsed("ТЕХНОЛОГИЯ БЛЮД РУССКОЙ КУХНИ (лек), Минаева С.В., а.. 26").roomText()).isEqualTo("26");
            assertThat(parsed("ИНФОРМАТИКА Миронов А.Н., А. 19").roomText()).isEqualTo("19");
        }

        @Test
        void buildingAndSpecialPlacesStayPartOfTheRoomText() {
            assertThat(parsed("ФИЗИКА Иванов И.И., а. 028 ВГУИТ").roomText()).isEqualTo("028 ВГУИТ");
            assertThat(parsed("ФИЗИКА Иванов И.И., а. 406 ВГУИТ ДИСТАНТ").roomText()).isEqualTo("406 ВГУИТ ДИСТАНТ");
            assertThat(parsed("БЖД Жесткова Т.В., а. 544 С и В").roomText()).isEqualTo("544 С и В");
            assertThat(parsed("ИСТОРИЯ Ипполитов О.О., а. Актовый зал").roomText()).isEqualTo("Актовый зал");
            assertThat(parsed("ФИЗИКА Иванов И.И., а. кафедры ТПЖП ВГУИТ").roomText()).isEqualTo("кафедры ТПЖП ВГУИТ");
        }

        @Test
        void roomWithoutAMarkerAfterTheTeacherIsStillARoom() {
            CellContent c = parsed("ОРГАНИЗАЦИЯ ДЕЯТЕЛЬНОСТИ ДЕПАРТАМЕНТА МАРКЕТИНГА И РЕКЛАМЫ (теория) Попова Н.Н., 540 С и В");

            assertThat(c.teacherText()).isEqualTo("Попова Н.Н.");
            assertThat(c.roomText()).isEqualTo("540 С и В");
        }

        @Test
        void markerWithoutAnyRoomMeansNoRoom() {
            CellContent c = parsed("РУССКИЙ ЯЗЫК       Крысанова О.А.,   а. ");

            assertThat(c.teacherText()).isEqualTo("Крысанова О.А.");
            assertThat(c.roomText()).isNull();
            assertThat(c.warnings()).isEmpty();
        }

        @Test
        void lessonWithoutTeacherAndRoom() {
            CellContent c = parsed("МАТЕМАТИКА");

            assertThat(c.subject()).isEqualTo("МАТЕМАТИКА");
            assertThat(c.teacherText()).isNull();
            assertThat(c.roomText()).isNull();
        }

        @Test
        void unclassifiedTextBetweenTeachersIsReportedNotDropped() {
            CellContent c = parsed("МДК.01.02 Процессы Косенко И.С. о, Иванов И.И., а. 5");

            assertThat(c.warnings()).extracting(Warning::code).containsExactly("UNPARSED_REMAINDER");
            assertThat(c.warnings().getFirst().message()).contains("о");
        }
    }

    @Nested
    class Unusable {

        @Test
        void blankAndZeroAreRejected() {
            assertThat(parser.parse("   ")).isInstanceOfSatisfying(Rejected.class, r -> assertThat(r.code()).isEqualTo("EMPTY_TEXT"));
            assertThat(parser.parse(null)).isInstanceOf(Rejected.class);
            assertThat(parser.parse("0")).isInstanceOfSatisfying(Rejected.class, r -> assertThat(r.code()).isEqualTo("ZERO_PLACEHOLDER"));
        }

        @Test
        void markersOnlyHaveNoSubject() {
            assertThat(parser.parse("(лаб.) 1 под.")).isInstanceOfSatisfying(Rejected.class,
                    r -> assertThat(r.code()).isEqualTo("EMPTY_SUBJECT"));
        }
    }
}
