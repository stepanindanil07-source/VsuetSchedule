package ru.vsuet.schedule.importer.parser.fuits;

import org.springframework.stereotype.Component;
import ru.vsuet.schedule.importer.parser.grid.GridFormat;
import ru.vsuet.schedule.importer.parser.grid.GridWorkbookParser;
import ru.vsuet.schedule.importer.parser.grid.LessonTextParser;

/**
 * Parser for the VSUET grid workbooks of the faculties that write lessons as
 * {@code пр.Дисциплина / Преподаватель   аудитория} (uits, itf, eht, tf, eiu .xlsx): one sheet per course,
 * groups across, days and two-row time slots down. All faculties checked so far share this layout, so one parser
 * serves them all; a faculty with a different layout gets its own parser and its own key (see the SPO parser).
 * The semester must be stated in the file.
 */
@Component
public class FuitsExcelParser extends GridWorkbookParser {

    public static final String KEY = "vsuet-grid-xlsx";
    static final String VERSION = "1";

    public FuitsExcelParser() {
        super(KEY, VERSION, new FuitsFormat(), true);
    }

    private static final class FuitsFormat implements GridFormat {

        private final CellTextParser textParser = new CellTextParser();

        @Override
        public LessonTextParser lessonTextParser() {
            return textParser;
        }
    }
}
