package ru.vsuet.schedule.importer.application;

import ru.vsuet.schedule.importer.domain.ImportStatus;

/** What an import attempt did. Counters refer to groups, {@code entriesWritten} to lessons inserted. */
public record ImportResult(ImportStatus status, long runId, String message, int groupsCreated, int groupsUpdated,
                           int groupsUnchanged, int groupsDeactivated, int entriesWritten) {

    static ImportResult unchanged(long runId) {
        return new ImportResult(ImportStatus.SKIPPED_UNCHANGED, runId, "File has not changed since the last import",
                0, 0, 0, 0, 0);
    }

    static ImportResult failed(long runId, String message) {
        return new ImportResult(ImportStatus.FAILED, runId, message, 0, 0, 0, 0, 0);
    }
}
