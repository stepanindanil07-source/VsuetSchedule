package ru.vsuet.schedule.importer.parser;

import java.io.InputStream;

/**
 * Turns one concrete schedule file format into the canonical {@link ParsedSchedule}.
 *
 * <p>Implementations must be pure: no database access, no network, no shared mutable state.
 * Each format (FUITS xlsx, SPO xls, ...) gets its own implementation.
 */
public interface ScheduleParser {

    /** Stable identifier stored in {@code schedule_source.parser_key}, e.g. "vsuet-grid-xlsx". */
    String key();

    /** Version of the parsing logic; bump it when output for the same file can change. */
    String version();

    /**
     * @throws ScheduleParseException if the file cannot be interpreted at all
     */
    ParsedSchedule parse(InputStream file);

    /**
     * Parses with what the importer knows about the source. Formats that are fully described by their file
     * (all of them but the correspondence one) ignore the context.
     */
    default ParsedSchedule parse(InputStream file, ParseContext context) {
        return parse(file);
    }
}
