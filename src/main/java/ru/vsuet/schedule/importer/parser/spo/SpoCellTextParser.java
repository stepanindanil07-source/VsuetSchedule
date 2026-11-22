package ru.vsuet.schedule.importer.parser.spo;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.vsuet.schedule.importer.parser.grid.LessonTextParser;
import ru.vsuet.schedule.schedule.domain.LessonType;

/**
 * Splits the free-form text of an SPO lesson cell, e.g.
 * <pre>
 *   ХИМИЯ     (лаб.)   1 под.     Черноусова О.В.,    а. 25 ВГУИТ
 * </pre>
 * into subject, lesson type, subgroup, teachers and room. The notation is not strict (repeated spaces, typos such
 * as "лоаб.", rooms like "а. С и В" or "спорт.зал"), so the parser works by recognising parts rather than by
 * position, and everything it does not recognise stays in the subject or the room text: nothing is thrown away.
 * Stateless and independent of Excel geometry.
 */
public final class SpoCellTextParser implements LessonTextParser {

    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern PARENTHESES = Pattern.compile("\\(([^()]*)\\)");
    /** "1 под." (Java's \\b is ASCII-only, so explicit look-arounds are used for Cyrillic). */
    private static final Pattern SUBGROUP = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])(\\d+)\\s*под(?:гр?)?(?![\\p{L}])\\.?");
    /**
     * The word "аудитория" abbreviated: "а. 25", "а.18", "а 26.", "а . БАР", "ауд. 32", "Ауд. 19". A bare "а" counts
     * only before a digit or a dot, so a stray conjunction in a subject is not taken for a room marker.
     */
    private static final Pattern ROOM_MARKER = Pattern.compile(
            "(?<![\\p{L}\\p{N}.])(?:[Аа]уд\\s*\\.?|а\\s*\\.|а(?=\\s*\\d))\\s*");
    /** After a teacher the marker may also be a bare "а" before a word ("а БАР"). */
    private static final Pattern ROOM_MARKER_AFTER_TEACHER = Pattern.compile(
            "(?<![\\p{L}\\p{N}.])(?:[Аа]уд\\s*\\.?|[Аа]\\s*\\.|[Аа](?=\\s*\\d)|а(?=\\s+\\p{L})|а(?=\\s*$))\\s*");
    /** Rooms written without "а.": sports facilities and the assembly hall. */
    private static final Pattern PLACE_WORDS = Pattern.compile(
            "(?iu)(спорт\\.?\\s*зал(?![\\p{L}]).*?(?<![\\p{L}])стадион|актовый\\s+зал|спорт\\.?\\s*зал|стадион)");
    /** "Фамилия И.О." with the spelling variants seen in the files: "Д." (one initial), "Н.А..", "Фомичев   Г.М.". */
    private static final Pattern TEACHER = Pattern.compile(
            "\\p{Lu}\\p{Ll}+(?:-\\p{Lu}\\p{Ll}+)?\\s+\\p{Lu}\\.(?:\\s*\\p{Lu}\\.?)?\\.*");

    @Override
    public Result parse(String text) {
        if (text == null || text.isBlank()) {
            return new Rejected("EMPTY_TEXT", "Cell text is empty");
        }
        if (text.strip().equals("0")) {
            return new Rejected("ZERO_PLACEHOLDER", "Cell contains the placeholder 0");
        }
        List<Warning> warnings = new ArrayList<>();
        String rest = SPACES.matcher(text).replaceAll(" ").strip();

        // 1. lesson type from a parenthesised marker; unknown parentheses stay in the subject
        EnumSet<LessonType> types = EnumSet.noneOf(LessonType.class);
        StringBuilder withoutMarkers = new StringBuilder();
        Matcher paren = PARENTHESES.matcher(rest);
        while (paren.find()) {
            EnumSet<LessonType> named = typesOf(paren.group(1));
            if (!named.isEmpty()) {
                types.addAll(named);
                paren.appendReplacement(withoutMarkers, " ");
            }
        }
        paren.appendTail(withoutMarkers);
        rest = withoutMarkers.toString();
        LessonType lessonType = LessonType.OTHER;
        if (types.size() == 1) {
            lessonType = types.iterator().next();
        } else if (types.size() > 1) {
            warnings.add(new Warning("AMBIGUOUS_LESSON_TYPE", "Cell names several lesson types " + types + "; set to OTHER"));
        }

        // 2. subgroup stated as "1 под."
        Integer subgroup = null;
        Matcher sg = SUBGROUP.matcher(rest);
        if (sg.find()) {
            subgroup = Integer.parseInt(sg.group(1));
            rest = (rest.substring(0, sg.start()) + " " + rest.substring(sg.end()));
        }

        // 3. teachers; the subject is whatever precedes the first one
        List<String> teachers = new ArrayList<>();
        Matcher teacher = TEACHER.matcher(rest);
        int firstTeacher = -1;
        int lastTeacherEnd = 0;
        List<int[]> teacherSpans = new ArrayList<>();
        while (teacher.find()) {
            if (firstTeacher < 0) {
                firstTeacher = teacher.start();
            }
            teachers.add(cleanName(teacher.group()));
            teacherSpans.add(new int[] {teacher.start(), teacher.end()});
            lastTeacherEnd = teacher.end();
        }

        // 4. room: everything after the first marker that follows the teachers; otherwise a known place name
        String room = null;
        int roomStart = rest.length();
        Matcher marker = (teachers.isEmpty() ? ROOM_MARKER : ROOM_MARKER_AFTER_TEACHER).matcher(rest);
        if (marker.find(lastTeacherEnd)) {
            room = cleanTail(rest.substring(marker.end()));
            roomStart = marker.start();
        } else {
            Matcher place = PLACE_WORDS.matcher(rest);
            if (place.find()) {
                room = SPACES.matcher(place.group()).replaceAll(" ").strip();
                rest = rest.substring(0, place.start()) + " " + rest.substring(place.end());
                // the text shifted: find the teachers again on the shortened text
                teachers.clear();
                teacherSpans.clear();
                firstTeacher = -1;
                Matcher again = TEACHER.matcher(rest);
                while (again.find()) {
                    if (firstTeacher < 0) {
                        firstTeacher = again.start();
                    }
                    teachers.add(cleanName(again.group()));
                    teacherSpans.add(new int[] {again.start(), again.end()});
                }
                roomStart = rest.length();
            }
        }

        // no marker at all, but text after the last teacher: that is where the lesson takes place ("Попова Н.Н., 540 С и В")
        if (room == null && !teacherSpans.isEmpty()) {
            int afterTeachers = teacherSpans.getLast()[1];
            String tail = cleanTail(rest.substring(afterTeachers));
            if (!tail.isEmpty()) {
                room = tail;
                roomStart = afterTeachers;
            }
        }

        String subject = firstTeacher >= 0 ? rest.substring(0, firstTeacher) : rest.substring(0, roomStart);
        // text between and after the teachers (before the room) that is neither punctuation nor part of a name
        StringBuilder leftover = new StringBuilder();
        for (int i = 0; i < teacherSpans.size(); i++) {
            int from = teacherSpans.get(i)[1];
            int to = i + 1 < teacherSpans.size() ? teacherSpans.get(i + 1)[0] : roomStart;
            if (to > from) {
                leftover.append(rest, from, to).append(' ');
            }
        }
        subject = cleanSubject(subject);
        String extra = leftover.toString().replaceAll("[\\p{P}\\s]+", " ").strip();
        if (!extra.isEmpty()) {
            warnings.add(new Warning("UNPARSED_REMAINDER", "Text after the teachers was not classified: '" + extra + "'"));
        }
        if (subject.isEmpty()) {
            return new Rejected("EMPTY_SUBJECT", "No subject found in the cell");
        }
        return new CellContent(lessonType, subject,
                teachers.isEmpty() ? null : String.join(", ", teachers),
                room == null || room.isEmpty() ? null : room,
                subgroup, warnings);
    }

    /** "лаб.", "лоаб.", "лабораторные занятия" -> LAB; "практические..." -> PRACTICE; "теория", "лек" -> LECTURE. */
    private static EnumSet<LessonType> typesOf(String marker) {
        String m = marker.toLowerCase(Locale.ROOT);
        EnumSet<LessonType> named = EnumSet.noneOf(LessonType.class);
        if (m.contains("лаб") || m.contains("лоаб")) {
            named.add(LessonType.LAB);
        }
        if (m.contains("практ") || m.contains("прак")) {
            named.add(LessonType.PRACTICE);
        }
        if (m.contains("теор") || m.contains("лек")) {
            named.add(LessonType.LECTURE);
        }
        return named;
    }

    private static String cleanTail(String tail) {
        return SPACES.matcher(tail).replaceAll(" ").replaceAll("^[\\s,.]+|[\\s,.]+$", "");
    }

    private static String cleanName(String name) {
        return SPACES.matcher(name).replaceAll(" ").strip().replaceAll("\\.{2,}$", ".");
    }

    private static String cleanSubject(String subject) {
        return SPACES.matcher(subject).replaceAll(" ").replaceAll("^[\\s,.;:-]+|[\\s,;:-]+$", "");
    }
}
