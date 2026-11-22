package ru.vsuet.schedule.importer.parser;

import java.util.List;
import java.util.Objects;

/**
 * Summary of a parsing run. Nothing is dropped silently: every skipped or suspicious record has an issue here.
 *
 * @param totalFound lessons the parser found in the source ({@code recognized + skipped})
 * @param recognized lessons turned into {@link ParsedEntry}
 * @param skipped    lessons that could not be interpreted
 */
public record ParseDiagnostics(String parserVersion, int totalFound, int recognized, int skipped, List<ParseIssue> issues) {

    public ParseDiagnostics {
        Objects.requireNonNull(parserVersion, "parserVersion");
        issues = List.copyOf(issues);
    }

    public long warningCount() {
        return countOf(IssueSeverity.WARNING);
    }

    public long errorCount() {
        return countOf(IssueSeverity.ERROR);
    }

    private long countOf(IssueSeverity severity) {
        return issues.stream().filter(i -> i.severity() == severity).count();
    }
}
