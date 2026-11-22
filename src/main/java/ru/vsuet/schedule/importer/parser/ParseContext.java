package ru.vsuet.schedule.importer.parser;

import java.time.LocalDate;

/**
 * What the importer knows about a file before parsing it, for formats that cannot be read from the file alone.
 *
 * @param periodStart      first day of the academic period the source is pinned to, or {@code null} when the source is not
 *                         pinned. The correspondence schedule writes dates as "12/10" without a year; the year is the one
 *                         that puts the date on or after this day.
 * @param academicYearFrom calendar year in which that period's academic year started (2026 for 2026-2027), or {@code null}.
 *                         Used to turn the admission year in a group code into a course. It is the pinned period's, not
 *                         the one written in the file: a file may state its semester wrongly (eiu.xlsx does).
 */
public record ParseContext(LocalDate periodStart, Integer academicYearFrom) {

    private static final ParseContext UNKNOWN = new ParseContext(null, null);

    /** A context from the period's first day alone; the academic year is the one the day falls in (from July on). */
    public ParseContext(LocalDate periodStart) {
        this(periodStart, periodStart == null ? null : (periodStart.getMonthValue() >= 7 ? periodStart.getYear() : periodStart.getYear() - 1));
    }

    /** Nothing is known: formats that need context fail with a clear message, the others do not care. */
    public static ParseContext unknown() {
        return UNKNOWN;
    }
}
