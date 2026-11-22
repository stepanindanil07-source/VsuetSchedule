package ru.vsuet.schedule.importer.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParserRegistryTest {

    private record StubParser(String key) implements ScheduleParser {
        @Override
        public String version() {
            return "test";
        }

        @Override
        public ParsedSchedule parse(InputStream file) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void returnsParserByKey() {
        StubParser fuits = new StubParser("fuits-xlsx");
        StubParser spo = new StubParser("spo-xls");
        ParserRegistry registry = new ParserRegistry(List.of(fuits, spo));

        assertThat(registry.get("spo-xls")).isSameAs(spo);
        assertThat(registry.get("fuits-xlsx")).isSameAs(fuits);
    }

    @Test
    void unknownKeyFailsLoudlyAndListsKnownKeys() {
        ParserRegistry registry = new ParserRegistry(List.of(new StubParser("fuits-xlsx")));

        assertThatThrownBy(() -> registry.get("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope")
                .hasMessageContaining("fuits-xlsx");
    }

    @Test
    void duplicateKeysAreRejectedAtStartup() {
        assertThatThrownBy(() -> new ParserRegistry(List.of(new StubParser("a"), new StubParser("a"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate parser key 'a'");
    }
}
