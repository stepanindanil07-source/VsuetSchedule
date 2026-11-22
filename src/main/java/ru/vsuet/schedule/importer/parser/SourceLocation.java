package ru.vsuet.schedule.importer.parser;

import java.util.Objects;

/**
 * Where in the source file a record came from, e.g. sheet "2 курс", range "H12:I13".
 * Format-neutral on purpose: other parsers may use a page, a table or a JSON path.
 */
public record SourceLocation(String container, String position) {

    public SourceLocation {
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(position, "position");
    }

    @Override
    public String toString() {
        return container + "!" + position;
    }
}
