package ru.vsuet.schedule.importer.domain;

public enum ImportStatus {
    RUNNING,
    SUCCESS,
    /** The file is byte-identical to the last successfully imported one; nothing was parsed or changed. */
    SKIPPED_UNCHANGED,
    FAILED
}
