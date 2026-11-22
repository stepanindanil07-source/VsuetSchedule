package ru.vsuet.schedule.importer.parser.zao;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.vsuet.schedule.schedule.domain.LessonType;

/**
 * Reads the four cells of one correspondence lesson (see {@link ZaoSheetParser}) into its parts:
 * <pre>
 *   subject row   "лаб. Технология бродильных производств- 2 п/г"   room
 *   teacher row   "Дядищева С.С."                                   (second room)
 * </pre>
 * A subject cell with several lines ("лаб. Основы диетологии\nПищевые добавки") means several parallel lessons of
 * the group written as one; they stay one entry, the subjects joined with " / ", just like two teachers and two
 * rooms are kept together. Stateless.
 */
final class ZaoLessonText {

    private static final Pattern TYPE_MARKER = Pattern.compile("^(\\p{Ll}{1,6})\\.\\s*(.*)$");
    private static final Pattern SUBGROUP = Pattern.compile("\\s*[-–]?\\s*(\\d+)\\s*п/г\\s*$");
    private static final Pattern SEVERAL_SPACES = Pattern.compile("\\s{2,}|\\n");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    record Warning(String code, String message) {
    }

    /** The parts of a lesson. {@code subgroup}, {@code teacher} and {@code room} may be {@code null}. */
    record Lesson(LessonType type, String subject, Integer subgroup, String teacher, String room, List<Warning> warnings) {
    }

    /** What could not be read: reported as a skipped lesson. */
    record Rejected(String code, String message) {
    }

    private ZaoLessonText() {
    }

    /** @return a {@link Lesson} or a {@link Rejected}; never both */
    static Object parse(String subjectCell, String teacherCell, String roomOnSubjectRow, String roomOnTeacherRow) {
        List<String> lines = subjectCell.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        if (lines.isEmpty()) {
            return new Rejected("LESSON_WITHOUT_SUBJECT", "Teacher or room without a subject");
        }
        List<Warning> warnings = new ArrayList<>();

        LessonType type = LessonType.OTHER;
        List<String> parts = new ArrayList<>(lines);
        Matcher marker = TYPE_MARKER.matcher(parts.getFirst());
        if (marker.matches()) {
            String code = marker.group(1);
            parts.set(0, marker.group(2));
            switch (code) {
                case "л" -> type = LessonType.LECTURE;
                case "пр" -> type = LessonType.PRACTICE;
                case "лаб" -> type = LessonType.LAB;
                case "экз" -> type = LessonType.EXAM;
                case "зач" -> type = LessonType.CREDIT;
                default -> warnings.add(new Warning("UNKNOWN_LESSON_MARKER",
                        "Unknown lesson type marker '" + code + ".'; lesson type set to OTHER"));
            }
        }

        Set<Integer> subgroups = new LinkedHashSet<>();
        List<String> subjects = new ArrayList<>();
        for (String part : parts) {
            Matcher sub = SUBGROUP.matcher(part);
            if (sub.find()) {
                subgroups.add(Integer.parseInt(sub.group(1)));
                part = part.substring(0, sub.start());
            }
            part = SPACES.matcher(part).replaceAll(" ").strip();
            if (!part.isEmpty()) {
                subjects.add(part);
            }
        }
        if (subjects.isEmpty()) {
            return new Rejected("EMPTY_SUBJECT", "Lesson marker without a subject: " + subjectCell.strip());
        }

        Integer subgroup = null;
        if (subgroups.size() == 1 && subgroups.iterator().next() >= 1) {
            subgroup = subgroups.iterator().next();
        } else if (subgroups.size() > 1) {
            warnings.add(new Warning("SUBGROUP_AMBIGUOUS", "The cell names several subgroups " + subgroups
                    + "; the lesson is shown to the whole group"));
        }

        return new Lesson(type, String.join(" / ", subjects), subgroup, joined(split(teacherCell)),
                joined(distinct(roomOnSubjectRow, roomOnTeacherRow)), warnings);
    }

    /** Teachers are separated by two or more spaces: "Карлов Д.А.  Выродов Д.М.". */
    private static List<String> split(String text) {
        List<String> result = new ArrayList<>();
        for (String piece : SEVERAL_SPACES.split(text.strip())) {
            String clean = SPACES.matcher(piece).replaceAll(" ").strip();
            if (!clean.isEmpty()) {
                result.add(clean);
            }
        }
        return result;
    }

    private static List<String> distinct(String... rooms) {
        Set<String> result = new LinkedHashSet<>();
        for (String room : rooms) {
            String clean = SPACES.matcher(room.strip()).replaceAll(" ");
            if (!clean.isEmpty()) {
                result.add(clean);
            }
        }
        return List.copyOf(result);
    }

    private static String joined(List<String> items) {
        return items.isEmpty() ? null : String.join(", ", items);
    }
}
