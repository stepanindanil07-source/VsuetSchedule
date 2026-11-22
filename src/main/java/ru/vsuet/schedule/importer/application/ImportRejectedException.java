package ru.vsuet.schedule.importer.application;

import java.util.List;
import ru.vsuet.schedule.importer.parser.ParseIssue;

/**
 * The file was parsed but must not be applied (period not registered, lesson count collapsed...).
 * Thrown inside the import transaction, so everything written so far is rolled back.
 */
class ImportRejectedException extends RuntimeException {

    private final transient List<ParseIssue> issues;

    ImportRejectedException(String message, List<ParseIssue> issues) {
        super(message);
        this.issues = List.copyOf(issues);
    }

    List<ParseIssue> issues() {
        return issues;
    }
}
