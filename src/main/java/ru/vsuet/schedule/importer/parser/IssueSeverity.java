package ru.vsuet.schedule.importer.parser;

public enum IssueSeverity {
    /** The record was kept, but something about it needs attention (unknown marker, missing room...). */
    WARNING,
    /** The record could not be interpreted and was skipped. */
    ERROR
}
