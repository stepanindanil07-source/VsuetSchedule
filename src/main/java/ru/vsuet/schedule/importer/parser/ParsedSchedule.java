package ru.vsuet.schedule.importer.parser;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Canonical result of parsing one source file. It knows nothing about Excel or the database:
 * every parser produces this shape and the import service consumes it.
 *
 * @param period the semester stated in the file, or {@code null} when the file does not state it (the import then
 *               requires the source to be pinned to a period)
 */
public record ParsedSchedule(
        String parserKey,
        ParsedPeriod period,
        List<ParsedGroup> groups,
        List<ParsedEntry> entries,
        ParseDiagnostics diagnostics) {

    public ParsedSchedule {
        Objects.requireNonNull(parserKey, "parserKey");
        Objects.requireNonNull(diagnostics, "diagnostics");
        groups = List.copyOf(groups);
        entries = List.copyOf(entries);

        Set<String> codes = groups.stream().map(ParsedGroup::code).collect(Collectors.toSet());
        if (codes.size() != groups.size()) {
            throw new IllegalArgumentException("Duplicate group codes in parsed schedule");
        }
        for (ParsedEntry entry : entries) {
            if (!codes.contains(entry.groupCode())) {
                throw new IllegalArgumentException("Entry refers to unknown group: " + entry.groupCode());
            }
        }
    }
}
