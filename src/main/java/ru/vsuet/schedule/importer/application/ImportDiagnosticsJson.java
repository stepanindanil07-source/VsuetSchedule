package ru.vsuet.schedule.importer.application;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;
import ru.vsuet.schedule.importer.parser.ParseDiagnostics;
import ru.vsuet.schedule.importer.parser.ParseIssue;
import ru.vsuet.schedule.importer.parser.IssueSeverity;

/** Serialises diagnostics into the JSON document stored in {@code import_run.diagnostics}. */
final class ImportDiagnosticsJson {

    /** A file with thousands of warnings must not produce a multi-megabyte row. */
    static final int MAX_STORED_ISSUES = 500;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private record Stored(String parserVersion, int totalFound, int recognized, int skipped, long warnings,
                          long errors, boolean truncated, List<ParseIssue> issues) {
    }

    private ImportDiagnosticsJson() {
    }

    /** @param parse may be {@code null} when parsing never produced diagnostics */
    static String write(ParseDiagnostics parse, List<ParseIssue> importIssues) {
        List<ParseIssue> all = new ArrayList<>();
        if (parse != null) {
            all.addAll(parse.issues());
        }
        all.addAll(importIssues);
        long warnings = all.stream().filter(i -> i.severity() == IssueSeverity.WARNING).count();
        long errors = all.stream().filter(i -> i.severity() == IssueSeverity.ERROR).count();
        boolean truncated = all.size() > MAX_STORED_ISSUES;
        List<ParseIssue> kept = truncated ? all.subList(0, MAX_STORED_ISSUES) : all;
        return MAPPER.writeValueAsString(new Stored(
                parse == null ? null : parse.parserVersion(),
                parse == null ? 0 : parse.totalFound(),
                parse == null ? 0 : parse.recognized(),
                parse == null ? 0 : parse.skipped(),
                warnings, errors, truncated, kept));
    }
}
