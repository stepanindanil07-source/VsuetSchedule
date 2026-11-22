package ru.vsuet.schedule.importer.parser;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Objects;
import ru.vsuet.schedule.schedule.domain.LessonType;
import ru.vsuet.schedule.common.WeekType;

/**
 * One lesson in the canonical, format-independent model.
 *
 * @param groupCode   code of the group this lesson belongs to; must match a {@link ParsedGroup}
 * @param subgroup    1-based subgroup number, or {@code null} when the lesson is for the whole group
 * @param teacherText teacher(s) as written in the source, {@code null} if absent (not normalised on purpose)
 * @param roomText    room(s) as written in the source, {@code null} if absent
 * @param rawText     original source text of the lesson, kept for diagnostics
 * @param location    where the lesson was found in the source file
 */
public record ParsedEntry(
        String groupCode,
        DayOfWeek dayOfWeek,
        LocalTime startTime,
        LocalTime endTime,
        WeekType weekType,
        Integer subgroup,
        String subject,
        LessonType lessonType,
        String teacherText,
        String roomText,
        String rawText,
        SourceLocation location) {

    public ParsedEntry {
        Objects.requireNonNull(groupCode, "groupCode");
        Objects.requireNonNull(dayOfWeek, "dayOfWeek");
        Objects.requireNonNull(startTime, "startTime");
        Objects.requireNonNull(endTime, "endTime");
        Objects.requireNonNull(weekType, "weekType");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(lessonType, "lessonType");
        Objects.requireNonNull(rawText, "rawText");
        Objects.requireNonNull(location, "location");
        if (!startTime.isBefore(endTime)) {
            throw new IllegalArgumentException("startTime must be before endTime: " + startTime + " - " + endTime);
        }
        if (subgroup != null && subgroup < 1) {
            throw new IllegalArgumentException("subgroup must be >= 1 or null, was " + subgroup);
        }
        if (subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
    }
}
