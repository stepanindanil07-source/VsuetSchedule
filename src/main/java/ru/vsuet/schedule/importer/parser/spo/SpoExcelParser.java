package ru.vsuet.schedule.importer.parser.spo;

import org.springframework.stereotype.Component;
import ru.vsuet.schedule.importer.parser.grid.GridFormat;
import ru.vsuet.schedule.importer.parser.grid.GridWorkbookParser;
import ru.vsuet.schedule.importer.parser.grid.LessonTextParser;

/**
 * Parser for the schedule of the Faculty of Secondary Vocational Education (ФСПО): {@code 1_kurs.xls} and
 * {@code 2-3-4_kurs.xls}. It reads the same kind of grid as the other faculties (groups across, days and two-row
 * slots down, numerator/denominator in the rows) but with the SPO notation:
 * <ul>
 *   <li>a lesson is one free-form line of text ({@link SpoCellTextParser}) with the subgroup written as "1 под.";</li>
 *   <li>time slots may state only the start ("1) 8 00"), the end is start + one pair;</li>
 *   <li>groups may be 1, 2 or 4 columns wide and many lessons are shared by several groups;</li>
 *   <li>2-3-4_kurs.xls does not state the semester: the source must be pinned to a period.</li>
 * </ul>
 */
@Component
public class SpoExcelParser extends GridWorkbookParser {

    public static final String KEY = "spo-xls";
    static final String VERSION = "1";

    public SpoExcelParser() {
        super(KEY, VERSION, new SpoFormat(), false);
    }

    private static final class SpoFormat implements GridFormat {

        private final SpoCellTextParser textParser = new SpoCellTextParser();

        @Override
        public LessonTextParser lessonTextParser() {
            return textParser;
        }

        /** "ИСП -255" and "ИСП-255" are the same group. */
        @Override
        public String normalizeGroupCode(String raw) {
            return raw.replaceAll("\\s+", " ").strip().replaceAll("\\s*-\\s*", "-");
        }

        /**
         * Groups four columns wide have lessons over two or three of the columns and no rule says whom they are for.
         * Showing such a lesson to the whole group (with a warning) is safer than hiding it.
         */
        @Override
        public PartialSpanPolicy partialSpanPolicy() {
            return PartialSpanPolicy.WHOLE_GROUP_WITH_WARNING;
        }
    }
}
