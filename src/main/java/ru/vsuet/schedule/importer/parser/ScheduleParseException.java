package ru.vsuet.schedule.importer.parser;

/**
 * Thrown when the source structure is so different from what the parser expects that no trustworthy
 * result can be produced (no header, no groups, no period...). The import is then aborted and the
 * current schedule stays untouched.
 */
public class ScheduleParseException extends RuntimeException {

    private final ParseDiagnostics diagnostics;

    public ScheduleParseException(String message, ParseDiagnostics diagnostics) {
        super(message);
        this.diagnostics = diagnostics;
    }

    public ScheduleParseException(String message, ParseDiagnostics diagnostics, Throwable cause) {
        super(message, cause);
        this.diagnostics = diagnostics;
    }

    /** Diagnostics collected before the failure; may be {@code null} if the file could not be read at all. */
    public ParseDiagnostics diagnostics() {
        return diagnostics;
    }
}
