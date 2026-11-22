package ru.vsuet.schedule.importer.parser.fuits;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.vsuet.schedule.schedule.domain.LessonType;

/**
 * Splits the text of one FUITS lesson cell into lesson type, subject, teachers and rooms.
 *
 * <p>Layout seen in uits.xlsx:
 * <pre>
 *   пр.История России              line 1: "&lt;type marker&gt;.&lt;subject&gt;"
 *   Гребенщиков А.Е.   46          line 2: teacher, 2+ spaces, room (may repeat for several teachers)
 * </pre>
 * Lines other than the first are "details". Whatever cannot be classified is kept, never dropped:
 * teachers and rooms stay free text (joined with ", "), unknown markers produce a {@link Warning}.
 * The class is stateless and knows nothing about Excel geometry.
 */
final class CellTextParser {

    private static final Pattern TYPE_MARKER = Pattern.compile("^(\\p{Ll}{1,6})\\.(?=\\S)(.*)$");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern DETAIL_SEPARATOR = Pattern.compile("\\s{2,}");
    /**
     * "Фамилия И.О." optionally followed by the rest of the token (a room written after a single space).
     * Tolerates the spelling variants met in the real files: "Е.В," / "Л.А" / "Д.В.." after the initials and
     * "Чекмарева_Пол" as a surname. The text is not "corrected", only recognised.
     */
    private static final Pattern TEACHER_PREFIX = Pattern.compile(
            "^(\\p{Lu}[\\p{L}_]+(?:-\\p{Lu}[\\p{L}_]+)?\\s+\\p{Lu}\\.\\s?\\p{Lu}[.,]*)(?:\\s+(.+))?$");

    sealed interface Result permits CellContent, Rejected {
    }

    /**
     * @param teacherText teachers joined with ", ", or {@code null} when the cell has none
     * @param roomText    rooms joined with ", ", or {@code null} when the cell has none
     */
    record CellContent(LessonType lessonType, String subject, String teacherText, String roomText,
                       List<Warning> warnings) implements Result {
        CellContent {
            warnings = List.copyOf(warnings);
        }
    }

    /** The text cannot be turned into a lesson; {@code code} is a stable machine-readable reason. */
    record Rejected(String code, String message) implements Result {
    }

    record Warning(String code, String message) {
    }

    Result parse(String text) {
        if (text == null || text.isBlank()) {
            return new Rejected("EMPTY_TEXT", "Cell text is empty");
        }
        if (text.strip().equals("0")) {
            return new Rejected("ZERO_PLACEHOLDER", "Cell contains the placeholder 0");
        }

        List<String> lines = text.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        List<Warning> warnings = new ArrayList<>();

        LessonType type = LessonType.OTHER;
        String subject = lines.getFirst();
        Matcher marker = TYPE_MARKER.matcher(subject);
        if (marker.matches()) {
            String code = marker.group(1);
            subject = marker.group(2);
            switch (code) {
                case "пр" -> type = LessonType.PRACTICE;
                case "л" -> type = LessonType.LECTURE;
                case "лаб" -> type = LessonType.LAB;
                default -> warnings.add(new Warning("UNKNOWN_LESSON_MARKER",
                        "Unknown lesson type marker '" + code + ".'; lesson type set to OTHER"));
            }
        } else if (isBareMarker(subject)) {
            return new Rejected("EMPTY_SUBJECT", "Lesson marker without a subject: " + subject);
        }
        subject = SPACES.matcher(subject).replaceAll(" ").strip();
        if (subject.isEmpty()) {
            return new Rejected("EMPTY_SUBJECT", "Lesson marker without a subject");
        }

        List<String> teachers = new ArrayList<>();
        List<String> rooms = new ArrayList<>();
        for (String detail : lines.subList(1, lines.size())) {
            parseDetailLine(detail, teachers, rooms, warnings);
        }

        return new CellContent(type, subject,
                teachers.isEmpty() ? null : String.join(", ", teachers),
                rooms.isEmpty() ? null : String.join(", ", rooms),
                warnings);
    }

    /** "пр." / "л." / "лаб." alone on the first line. */
    private static boolean isBareMarker(String line) {
        return line.matches("^\\p{Ll}{1,6}\\.$");
    }

    private static void parseDetailLine(String line, List<String> teachers, List<String> rooms, List<Warning> warnings) {
        for (String token : DETAIL_SEPARATOR.split(line)) {
            token = token.strip();
            if (token.isEmpty()) {
                continue;
            }
            Matcher teacher = TEACHER_PREFIX.matcher(token);
            if (teacher.matches()) {
                // a trailing comma is a separator artefact of the cell, not part of the name
                teachers.add(SPACES.matcher(teacher.group(1)).replaceAll(" ").replaceAll(",+$", ""));
                if (teacher.group(2) != null) {
                    rooms.add(SPACES.matcher(teacher.group(2)).replaceAll(" "));
                }
            } else if (!SPACES.matcher(token).find()) {
                rooms.add(token);
            } else {
                // Several words but not "Фамилия И.О.": more likely a malformed name than a room.
                teachers.add(SPACES.matcher(token).replaceAll(" "));
                warnings.add(new Warning("UNRECOGNIZED_DETAIL",
                        "Cannot tell whether '" + token + "' is a teacher or a room; kept as teacher"));
            }
        }
    }
}
