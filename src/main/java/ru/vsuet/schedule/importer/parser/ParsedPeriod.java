package ru.vsuet.schedule.importer.parser;

import java.util.Objects;
import ru.vsuet.schedule.academic.domain.AcademicTerm;

/** Academic period (semester) the schedule belongs to, e.g. spring 2025-2026. */
public record ParsedPeriod(int yearFrom, int yearTo, AcademicTerm term) {

    public ParsedPeriod {
        Objects.requireNonNull(term, "term");
        if (yearTo != yearFrom + 1) {
            throw new IllegalArgumentException("Academic year must span two consecutive years: " + yearFrom + "-" + yearTo);
        }
    }
}
