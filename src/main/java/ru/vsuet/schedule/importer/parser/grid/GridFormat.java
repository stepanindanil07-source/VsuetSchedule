package ru.vsuet.schedule.importer.parser.grid;

/**
 * The few places where VSUET grid workbooks differ from each other. Everything else (finding the header,
 * groups, days, time slots and reading week/subgroup from merged cells) is shared in {@link GridSheetParser}.
 */
public interface GridFormat {

    /** How a lesson cell is written in this format. */
    LessonTextParser lessonTextParser();

    /** Normalises a group code read from the header cell. */
    default String normalizeGroupCode(String raw) {
        return raw.strip();
    }

    /** What to do with a lesson that covers some, but not all, columns of a group (and is not a single column). */
    default PartialSpanPolicy partialSpanPolicy() {
        return PartialSpanPolicy.SKIP_WITH_ERROR;
    }

    enum PartialSpanPolicy {
        /** Skip the lesson and report an error: its audience cannot be determined. */
        SKIP_WITH_ERROR,
        /** Keep the lesson for the whole group and report a warning: better an extra lesson than a hidden one. */
        WHOLE_GROUP_WITH_WARNING
    }
}
