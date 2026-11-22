package ru.vsuet.schedule.importer.parser.grid;

import java.util.List;
import ru.vsuet.schedule.schedule.domain.LessonType;

/**
 * Splits the text of one lesson cell into its parts. The cell notation differs between faculties, so each
 * format brings its own implementation; the grid geometry code does not care how the text is written.
 * Implementations are stateless and never drop information they cannot classify.
 */
public interface LessonTextParser {

    Result parse(String text);

    sealed interface Result permits CellContent, Rejected {
    }

    /**
     * @param teacherText teachers joined with ", ", or {@code null} when the cell has none
     * @param roomText    rooms joined with ", ", or {@code null} when the cell has none
     * @param subgroup    subgroup number stated in the TEXT of the cell ("1 под."), or {@code null}
     */
    record CellContent(LessonType lessonType, String subject, String teacherText, String roomText, Integer subgroup,
                       List<Warning> warnings) implements Result {
        public CellContent {
            warnings = List.copyOf(warnings);
        }
    }

    /** The text cannot be turned into a lesson; {@code code} is a stable machine-readable reason. */
    record Rejected(String code, String message) implements Result {
    }

    record Warning(String code, String message) {
    }
}
