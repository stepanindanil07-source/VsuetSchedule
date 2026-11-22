package ru.vsuet.schedule.importer.parser;

import java.util.Objects;

/**
 * A study group found in the source, e.g. "У-251".
 *
 * @param course year of study as the file states it (1-6), or {@code null} when the file does not say it for this
 *               group unambiguously (e.g. a sheet "1, 2 и 5 курсов" that mixes courses)
 */
public record ParsedGroup(String code, Integer course) {

    public static final int MAX_COURSE = 6;

    public ParsedGroup {
        Objects.requireNonNull(code, "code");
        if (code.isBlank()) {
            throw new IllegalArgumentException("Group code must not be blank");
        }
        if (course != null && (course < 1 || course > MAX_COURSE)) {
            throw new IllegalArgumentException("Course must be 1-" + MAX_COURSE + ", got " + course);
        }
    }

    /** A group whose course is unknown. */
    public ParsedGroup(String code) {
        this(code, null);
    }
}
