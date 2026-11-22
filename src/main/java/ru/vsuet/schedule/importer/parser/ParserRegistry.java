package ru.vsuet.schedule.importer.parser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Finds the parser for a source by its {@code parser_key}. The key is configured explicitly per source
 * (no guessing by file content), so a wrong parser can never be picked silently.
 */
@Component
public class ParserRegistry {

    private final Map<String, ScheduleParser> parsersByKey = new HashMap<>();

    public ParserRegistry(List<ScheduleParser> parsers) {
        for (ScheduleParser parser : parsers) {
            ScheduleParser previous = parsersByKey.put(parser.key(), parser);
            if (previous != null) {
                throw new IllegalStateException("Duplicate parser key '" + parser.key() + "': "
                        + previous.getClass().getName() + " and " + parser.getClass().getName());
            }
        }
    }

    public ScheduleParser get(String key) {
        ScheduleParser parser = parsersByKey.get(key);
        if (parser == null) {
            throw new IllegalArgumentException("No parser registered for key '" + key
                    + "'. Known keys: " + parsersByKey.keySet());
        }
        return parser;
    }
}
