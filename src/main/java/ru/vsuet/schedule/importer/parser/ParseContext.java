package ru.vsuet.schedule.importer.parser;

import java.time.LocalDate;

/**
 * What the importer knows about a file before parsing it, for formats that cannot be read from the file alone.
 *
 * @param periodStart first day of the academic period the source is pinned to, or {@code null} when the source is not
 *                    pinned. The correspondence schedule writes dates as "12/10" without a year; the year is the one
 *                    that puts the date on or after this day.
 */
public record ParseContext(LocalDate periodStart) {

    private static final ParseContext UNKNOWN = new ParseContext(null);

    /** Nothing is known: formats that need context fail with a clear message, the others do not care. */
    public static ParseContext unknown() {
        return UNKNOWN;
    }
}
