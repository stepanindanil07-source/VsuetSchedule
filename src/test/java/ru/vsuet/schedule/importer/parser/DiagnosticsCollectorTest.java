package ru.vsuet.schedule.importer.parser;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DiagnosticsCollectorTest {

    private static final SourceLocation LOCATION = new SourceLocation("2 курс", "H13");

    @Test
    void emptyCollectorProducesZeroCounters() {
        ParseDiagnostics d = new DiagnosticsCollector().build("1.0");

        assertThat(d.totalFound()).isZero();
        assertThat(d.issues()).isEmpty();
        assertThat(d.parserVersion()).isEqualTo("1.0");
    }

    @Test
    void countsRecognizedAndSkippedEntries() {
        DiagnosticsCollector c = new DiagnosticsCollector();
        c.entryRecognized();
        c.entryRecognized();
        c.entrySkipped("EMPTY_SUBJECT", "No subject", LOCATION, "пр.");

        ParseDiagnostics d = c.build("1.0");

        assertThat(d.totalFound()).isEqualTo(3);
        assertThat(d.recognized()).isEqualTo(2);
        assertThat(d.skipped()).isEqualTo(1);
        assertThat(d.errorCount()).isEqualTo(1);
    }

    @Test
    void warningDoesNotChangeEntryCounters() {
        DiagnosticsCollector c = new DiagnosticsCollector();
        c.entryRecognized();
        c.warn("UNKNOWN_ROOM", "Room 'Z' is not a known format", LOCATION, "пр.Физика\nИванов И.И.   Z");

        ParseDiagnostics d = c.build("1.0");

        assertThat(d.recognized()).isEqualTo(1);
        assertThat(d.skipped()).isZero();
        assertThat(d.warningCount()).isEqualTo(1);
        assertThat(d.issues().getFirst().location()).isEqualTo(LOCATION);
    }
}
