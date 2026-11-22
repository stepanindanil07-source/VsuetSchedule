package ru.vsuet.schedule.importer.parser;

import java.util.ArrayList;
import java.util.List;

/** Mutable helper a parser uses while it works; {@link #build} produces the immutable {@link ParseDiagnostics}. */
public final class DiagnosticsCollector {

    private final List<ParseIssue> issues = new ArrayList<>();
    private int recognized;
    private int skipped;

    /** A lesson was converted into an entry. */
    public void entryRecognized() {
        recognized++;
    }

    /** A lesson was found but could not be interpreted; it is reported as an ERROR issue. */
    public void entrySkipped(String code, String message, SourceLocation location, String rawText) {
        skipped++;
        issues.add(new ParseIssue(IssueSeverity.ERROR, code, message, location, rawText));
    }

    /** Something needs attention but the record is still kept. */
    public void warn(String code, String message, SourceLocation location, String rawText) {
        issues.add(new ParseIssue(IssueSeverity.WARNING, code, message, location, rawText));
    }

    public ParseDiagnostics build(String parserVersion) {
        return new ParseDiagnostics(parserVersion, recognized + skipped, recognized, skipped, issues);
    }
}
