package ru.vsuet.schedule.importer.parser;

import java.util.Objects;

/** A study group found in the source, e.g. "У-251". */
public record ParsedGroup(String code) {

    public ParsedGroup {
        Objects.requireNonNull(code, "code");
        if (code.isBlank()) {
            throw new IllegalArgumentException("Group code must not be blank");
        }
    }
}
