package ru.vsuet.schedule.importer.parser;

import java.util.Objects;

/**
 * One diagnostic message about the source file.
 *
 * @param code     stable machine-readable code, e.g. "UNKNOWN_LESSON_MARKER"
 * @param location where in the source the problem is, may be {@code null} for file-level problems
 * @param rawText  the offending source text, may be {@code null}
 */
public record ParseIssue(IssueSeverity severity, String code, String message, SourceLocation location, String rawText) {

    public ParseIssue {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
