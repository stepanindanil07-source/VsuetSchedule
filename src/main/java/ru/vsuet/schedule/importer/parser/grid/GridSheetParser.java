package ru.vsuet.schedule.importer.parser.grid;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import ru.vsuet.schedule.common.WeekType;
import ru.vsuet.schedule.importer.parser.DiagnosticsCollector;
import ru.vsuet.schedule.importer.parser.ParsedEntry;
import ru.vsuet.schedule.importer.parser.ParsedGroup;
import ru.vsuet.schedule.importer.parser.SourceLocation;
import ru.vsuet.schedule.importer.parser.grid.LessonTextParser.CellContent;
import ru.vsuet.schedule.importer.parser.grid.LessonTextParser.Rejected;
import ru.vsuet.schedule.importer.parser.grid.LessonTextParser.Result;

/**
 * Parses one sheet of a VSUET "grid" workbook: groups across, days and two-row time slots down.
 * Nothing here relies on fixed row or column numbers. The header is found by its "Дни" label, groups and
 * their column spans come from merged cells of the group header row, days and time slots come from merged
 * ranges in the label columns, and the week/subgroup of a lesson is derived from the geometry of its merged
 * range inside the slot x group rectangle.
 *
 * <pre>
 *   slot (2 rows)               group with 2 columns
 *   top row    = numerator      left column  = subgroup 1
 *   bottom row = denominator    right column = subgroup 2
 * </pre>
 * A range covering both rows is a lesson every week; covering all columns of a group, a lesson for the whole
 * group; covering several whole neighbouring groups, a shared lesson (repeated for each of them).
 * One instance per parse run (it holds a stateful {@link DataFormatter}).
 */
final class GridSheetParser {

    private static final Pattern DAYS_LABEL = Pattern.compile("(?iu)^\\s*дни(\\s+недели)?\\s*$");
    private static final Pattern TIME_LABEL = Pattern.compile("(?iu)^\\s*(часы(\\s+звонков)?|время)\\s*$");
    private static final int HEADER_SEARCH_LIMIT = 100;
    private static final int EXPECTED_SLOT_HEIGHT = 2;
    /** Length of one pair. Every explicit "start-end" interval in the real files is exactly this long. */
    private static final int LESSON_MINUTES = 95;

    private static final Map<String, DayOfWeek> DAYS = Map.of(
            "ПОНЕДЕЛЬНИК", DayOfWeek.MONDAY, "ВТОРНИК", DayOfWeek.TUESDAY, "СРЕДА", DayOfWeek.WEDNESDAY,
            "ЧЕТВЕРГ", DayOfWeek.THURSDAY, "ПЯТНИЦА", DayOfWeek.FRIDAY, "СУББОТА", DayOfWeek.SATURDAY,
            "ВОСКРЕСЕНЬЕ", DayOfWeek.SUNDAY);
    private static final Pattern TIME_RANGE = Pattern.compile("(\\d{1,2})[.: ](\\d{2})\\s*[-–]\\s*(\\d{1,2})[.: ](\\d{2})");
    /** A bare start time, optionally numbered: "1) 8 00". */
    private static final Pattern TIME_START = Pattern.compile("^\\s*(?:\\d+\\)\\s*)?(\\d{1,2})[.: ](\\d{2})\\s*$");
    private static final Pattern SUBGROUP_IN_TEXT = Pattern.compile("(\\d+)\\s*п/г");

    /** Group columns: {@code firstCol..lastCol} inclusive, zero-based. */
    private record GroupColumns(String code, int firstCol, int lastCol) {
        int width() {
            return lastCol - firstCol + 1;
        }
    }

    private record TimeSlot(int firstRow, int lastRow, LocalTime start, LocalTime end) {
        int height() {
            return lastRow - firstRow + 1;
        }
    }

    /** Where the header labels are. */
    private record Header(int labelRow, int dayCol, int timeCol, int groupRow, int firstDataRow) {
    }

    record SheetResult(List<ParsedGroup> groups, List<ParsedEntry> entries) {
    }

    private final DataFormatter formatter = new DataFormatter();
    private final GridFormat format;
    private final LessonTextParser textParser;
    private final DiagnosticsCollector diagnostics;

    private Sheet sheet;
    private MergedRegions merged;
    private List<GroupColumns> allGroups = List.of();
    private int slotsWithDerivedEnd;
    private SourceLocation firstDerivedEnd;

    GridSheetParser(GridFormat format, DiagnosticsCollector diagnostics) {
        this.format = format;
        this.textParser = format.lessonTextParser();
        this.diagnostics = diagnostics;
    }

    /** Row index (zero-based) of the row holding the "Дни" label, or -1 if the sheet has none. */
    static int findHeaderRow(Sheet sheet) {
        DataFormatter fmt = new DataFormatter();
        int limit = Math.min(sheet.getLastRowNum(), HEADER_SEARCH_LIMIT);
        for (int r = sheet.getFirstRowNum(); r <= limit; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            for (Cell cell : row) {
                if (DAYS_LABEL.matcher(fmt.formatCellValue(cell)).matches()) {
                    return r;
                }
            }
        }
        return -1;
    }

    /** @return the parsed sheet, or {@code null} if the header has no time column or no groups (reported). */
    SheetResult parse(Sheet sheet, int labelRow) {
        this.sheet = sheet;
        this.merged = new MergedRegions(sheet);
        this.slotsWithDerivedEnd = 0;
        this.firstDerivedEnd = null;

        Header header = locateHeader(labelRow);
        if (header == null) {
            return null;
        }
        List<GroupColumns> groups = readGroups(header);
        this.allGroups = groups;
        if (groups.isEmpty()) {
            diagnostics.warn("SHEET_NO_GROUPS", "No group codes found right of the time column",
                    location(header.groupRow(), header.timeCol()), null);
            return null;
        }

        List<ParsedEntry> entries = new ArrayList<>();
        for (int[] block : findDayBlocks(header)) {
            DayOfWeek day = DAYS.get(normalize(text(block[0], header.dayCol())));
            List<TimeSlot> slots = findSlots(block[0], block[1], header.timeCol());
            for (TimeSlot slot : slots) {
                for (GroupColumns group : groups) {
                    parseGroupInSlot(day, slot, group, entries);
                }
            }
            reportTextOutsideSlots(block, slots, groups);
        }
        if (slotsWithDerivedEnd > 0) {
            diagnostics.warn("SLOT_END_DERIVED", slotsWithDerivedEnd + " time slot(s) state only the start time; the end was "
                    + "set to start + " + LESSON_MINUTES + " minutes (the length of every explicit interval in the files)",
                    firstDerivedEnd, null);
        }
        return new SheetResult(groups.stream().map(g -> new ParsedGroup(g.code())).toList(), entries);
    }

    // ---- structure ---------------------------------------------------------------------------------------

    /**
     * The "Дни" and time labels may be merged over several rows (SPO), with the group codes one row lower. The group
     * row is the row, within the rows the labels span, with the most filled cells right of the time column (a band
     * title such as "10 МЕСЯЦЕВ" above the group codes has one cell, the codes have many); lessons start below
     * everything the header occupies.
     */
    private Header locateHeader(int labelRow) {
        int dayCol = findLabel(labelRow, DAYS_LABEL);
        int timeCol = findLabel(labelRow, TIME_LABEL);
        if (timeCol < 0) {
            diagnostics.warn("SHEET_NO_TIME_COLUMN", "Header row has no time column label (Часы / время)",
                    location(labelRow, dayCol), null);
            return null;
        }
        int labelLastRow = Math.max(lastRowOf(labelRow, dayCol), lastRowOf(labelRow, timeCol));
        int groupRow = labelRow;
        int mostFilled = 0;
        for (int r = labelRow; r <= labelLastRow; r++) {
            int filled = filledCellsRightOf(r, timeCol);
            if (filled > mostFilled) {
                mostFilled = filled;
                groupRow = r;
            }
        }
        int firstDataRow = labelLastRow + 1;
        Row row = sheet.getRow(groupRow);
        if (row != null) {
            for (Cell cell : row) {
                if (cell.getColumnIndex() > timeCol && !formatter.formatCellValue(cell).isBlank()) {
                    firstDataRow = Math.max(firstDataRow, lastRowOf(groupRow, cell.getColumnIndex()) + 1);
                }
            }
        }
        return new Header(labelRow, dayCol, timeCol, groupRow, firstDataRow);
    }

    private int lastRowOf(int row, int col) {
        CellRangeAddress range = merged.at(row, col);
        return range != null ? range.getLastRow() : row;
    }

    private int filledCellsRightOf(int rowIndex, int col) {
        Row row = sheet.getRow(rowIndex);
        if (row == null) {
            return 0;
        }
        int count = 0;
        for (Cell cell : row) {
            if (cell.getColumnIndex() > col && !formatter.formatCellValue(cell).isBlank()) {
                count++;
            }
        }
        return count;
    }

    private int findLabel(int row, Pattern label) {
        Row r = sheet.getRow(row);
        for (Cell cell : r) {
            if (label.matcher(formatter.formatCellValue(cell)).matches()) {
                return cell.getColumnIndex();
            }
        }
        return -1;
    }

    private List<GroupColumns> readGroups(Header header) {
        List<GroupColumns> groups = new ArrayList<>();
        Row row = sheet.getRow(header.groupRow());
        for (Cell cell : row) {
            int col = cell.getColumnIndex();
            String code = format.normalizeGroupCode(formatter.formatCellValue(cell));
            if (col <= header.timeCol() || code.isEmpty()) {
                continue;
            }
            CellRangeAddress range = merged.at(header.groupRow(), col);
            int last = range != null ? range.getLastColumn() : col;
            groups.add(new GroupColumns(code, col, last));
        }
        return groups;
    }

    /** Rows {@code [first, last]} of every recognised day block; unknown day names are reported and skipped. */
    private List<int[]> findDayBlocks(Header header) {
        List<int[]> blocks = new ArrayList<>();
        int lastRow = sheet.getLastRowNum();
        for (int r = header.firstDataRow(); r <= lastRow; r++) {
            String name = text(r, header.dayCol());
            if (name.isBlank()) {
                continue;
            }
            CellRangeAddress range = merged.at(r, header.dayCol());
            int end = range != null ? range.getLastRow() : r;
            if (DAYS.containsKey(normalize(name))) {
                blocks.add(new int[] {r, end});
            } else {
                diagnostics.warn("UNKNOWN_DAY", "Unrecognised day name '" + name.strip() + "'; its rows were not read",
                        location(r, header.dayCol()), name);
            }
            r = end;
        }
        return blocks;
    }

    private List<TimeSlot> findSlots(int firstRow, int lastRow, int timeCol) {
        List<TimeSlot> slots = new ArrayList<>();
        for (int r = firstRow; r <= lastRow; r++) {
            String time = text(r, timeCol);
            if (time.isBlank()) {
                continue;
            }
            CellRangeAddress range = merged.at(r, timeCol);
            int end = range != null ? range.getLastRow() : r;
            TimeSlot slot = readSlot(time, r, end);
            if (slot != null) {
                slots.add(slot);
            } else {
                diagnostics.warn("UNPARSEABLE_TIME", "Cannot read time interval '" + time.strip() + "'; its rows were not read",
                        location(r, timeCol), time);
            }
            r = end;
        }
        return slots;
    }

    /** Reads "08.00-09.35", "8.00-9.35" or just the start ("1) 8 00", end = start + one pair). */
    private TimeSlot readSlot(String time, int firstRow, int lastRow) {
        Matcher range = TIME_RANGE.matcher(time);
        if (range.find()) {
            return new TimeSlot(firstRow, lastRow,
                    LocalTime.of(Integer.parseInt(range.group(1)), Integer.parseInt(range.group(2))),
                    LocalTime.of(Integer.parseInt(range.group(3)), Integer.parseInt(range.group(4))));
        }
        Matcher start = TIME_START.matcher(time);
        if (start.matches()) {
            LocalTime from = LocalTime.of(Integer.parseInt(start.group(1)), Integer.parseInt(start.group(2)));
            slotsWithDerivedEnd++;
            if (firstDerivedEnd == null) {
                firstDerivedEnd = new SourceLocation(sheet.getSheetName(), new CellReference(firstRow, 0).formatAsString());
            }
            return new TimeSlot(firstRow, lastRow, from, from.plusMinutes(LESSON_MINUTES));
        }
        return null;
    }

    // ---- lessons -----------------------------------------------------------------------------------------

    /**
     * Lesson text in rows of a day block that belong to no time slot (the time cell is shifted against the lessons)
     * cannot be placed in time. It is reported as a skipped lesson instead of being ignored silently.
     */
    private void reportTextOutsideSlots(int[] block, List<TimeSlot> slots, List<GroupColumns> groups) {
        for (int r = block[0]; r <= block[1]; r++) {
            final int row = r;
            if (slots.stream().anyMatch(sl -> row >= sl.firstRow() && row <= sl.lastRow())) {
                continue;
            }
            for (GroupColumns group : groups) {
                for (int c = group.firstCol(); c <= group.lastCol(); c++) {
                    String text = text(r, c);
                    CellRangeAddress range = merged.at(r, c);
                    if (text.isBlank() || (range != null && !MergedRegions.isAnchor(range, r, c))) {
                        continue;
                    }
                    diagnostics.entrySkipped("TEXT_OUTSIDE_TIME_SLOT",
                            "Lesson text lies in rows that belong to no time slot, so its time is unknown",
                            location(range, r, c), text);
                }
            }
        }
    }

    private void parseGroupInSlot(DayOfWeek day, TimeSlot slot, GroupColumns group, List<ParsedEntry> out) {
        for (int r = slot.firstRow(); r <= slot.lastRow(); r++) {
            for (int c = group.firstCol(); c <= group.lastCol(); c++) {
                String text = text(r, c);
                CellRangeAddress range = merged.at(r, c);
                if (range != null && !MergedRegions.isAnchor(range, r, c)) {
                    if (!text.isBlank()) {
                        diagnostics.warn("HIDDEN_TEXT_IN_MERGED_CELL",
                                "Text in a non-anchor cell of a merged range is ignored by Excel and was not read",
                                location(r, c), text);
                    }
                    continue;
                }
                if (text.isBlank()) {
                    continue;
                }
                parseLesson(day, slot, group, r, c, range, text, out);
            }
        }
    }

    private void parseLesson(DayOfWeek day, TimeSlot slot, GroupColumns group, int row, int col,
                             CellRangeAddress range, String text, List<ParsedEntry> out) {
        Result result = textParser.parse(text);
        SourceLocation where = location(range, row, col);
        if (result instanceof Rejected rejected) {
            if (!rejected.code().equals("ZERO_PLACEHOLDER")) {
                diagnostics.entrySkipped(rejected.code(), rejected.message(), where, text);
            }
            return;
        }
        CellContent content = (CellContent) result;

        int r1 = range != null ? range.getFirstRow() : row;
        int r2 = range != null ? range.getLastRow() : row;
        int c1 = range != null ? range.getFirstColumn() : col;
        int c2 = range != null ? range.getLastColumn() : col;

        if (r1 < slot.firstRow() || r2 > slot.lastRow() || c1 < group.firstCol()) {
            diagnostics.entrySkipped("MERGE_CROSSES_BOUNDARY",
                    "Merged range leaves its time slot or group columns", where, text);
            return;
        }
        WeekType week = weekType(slot, r1, r2);
        if (week == null) {
            diagnostics.entrySkipped("UNEXPECTED_SLOT_HEIGHT", "Time slot spans " + slot.height()
                    + " rows instead of " + EXPECTED_SLOT_HEIGHT + "; numerator/denominator cannot be told apart", where, text);
            return;
        }

        // A lesson shared by several groups (a stream) is one cell merged over neighbouring groups. It is repeated for
        // each of them. If the range ends (or starts) inside a group, that group is only partly covered: the format's
        // policy decides between rejecting the lesson and showing it to that whole group with a warning.
        if (c2 > group.lastCol()) {
            List<GroupSpan> spans = groupsIntersecting(c1, c2);
            boolean anyPartial = spans.stream().anyMatch(sp -> !sp.full());
            if (spans.isEmpty() || (anyPartial && format.partialSpanPolicy() == GridFormat.PartialSpanPolicy.SKIP_WITH_ERROR)) {
                diagnostics.entrySkipped("MERGE_CROSSES_BOUNDARY",
                        "Merged range cuts through a group instead of covering whole groups", where, text);
                return;
            }
            for (GroupSpan span : spans) {
                if (!span.full()) {
                    diagnostics.warn("PARTIAL_SPAN_AS_WHOLE_GROUP", "Shared lesson covers only part of group "
                            + span.group().code() + " and names no subgroup there; shown for the whole group", where, text);
                }
                emit(day, slot, span.group(), week, content.subgroup(), content, text, where, out);
            }
            return;
        }

        Integer subgroup = resolveSubgroup(group, c1, c2, content, where, text);
        if (subgroup == null && !isWholeGroup(group, c1, c2) && c1 != c2 && content.subgroup() == null
                && format.partialSpanPolicy() == GridFormat.PartialSpanPolicy.SKIP_WITH_ERROR) {
            diagnostics.entrySkipped("PARTIAL_GROUP_SPAN",
                    "Lesson covers some but not all of " + group.width() + " subgroup columns", where, text);
            return;
        }
        emit(day, slot, group, week, subgroup, content, text, where, out);
    }

    /**
     * Subgroup of a lesson inside one group. A subgroup stated in the cell text wins (SPO writes "1 под."); otherwise
     * the column decides: whole width = whole group, one column = that subgroup. A lesson over some but not all of
     * several columns follows the format's policy.
     */
    private Integer resolveSubgroup(GroupColumns group, int c1, int c2, CellContent content, SourceLocation where, String raw) {
        Integer fromText = content.subgroup();
        boolean whole = isWholeGroup(group, c1, c2);
        Integer fromColumn = !whole && c1 == c2 ? c1 - group.firstCol() + 1 : null;
        if (fromText != null) {
            if (fromColumn != null && !fromColumn.equals(fromText)) {
                diagnostics.warn("SUBGROUP_MISMATCH", "Text says subgroup " + fromText + " but the column says subgroup "
                        + fromColumn + "; the text is used", where, raw);
            }
            return fromText;
        }
        if (whole) {
            return null;
        }
        if (fromColumn != null) {
            return fromColumn;
        }
        if (format.partialSpanPolicy() == GridFormat.PartialSpanPolicy.WHOLE_GROUP_WITH_WARNING) {
            diagnostics.warn("PARTIAL_SPAN_AS_WHOLE_GROUP", "Lesson covers some but not all of the " + group.width()
                    + " columns of the group and names no subgroup; shown for the whole group", where, raw);
        }
        return null;
    }

    private static boolean isWholeGroup(GroupColumns group, int c1, int c2) {
        return c1 == group.firstCol() && c2 == group.lastCol();
    }

    /** A group touched by a merged range, and whether the range covers all of the group's columns. */
    private record GroupSpan(GroupColumns group, boolean full) {
    }

    /**
     * Groups that a range over columns {@code c1..c2} touches, in order. Empty if the range is not laid over
     * neighbouring groups from end to end (a gap, or columns that belong to no group).
     */
    private List<GroupSpan> groupsIntersecting(int c1, int c2) {
        List<GroupSpan> spans = new ArrayList<>();
        int expectedNext = -1;
        for (GroupColumns g : allGroups) {
            if (g.lastCol() < c1 || g.firstCol() > c2) {
                continue;
            }
            if (expectedNext != -1 && g.firstCol() != expectedNext) {
                return List.of();
            }
            if (expectedNext == -1 && g.firstCol() > c1) {
                return List.of();
            }
            spans.add(new GroupSpan(g, g.firstCol() >= c1 && g.lastCol() <= c2));
            expectedNext = g.lastCol() + 1;
        }
        return !spans.isEmpty() && expectedNext > c2 ? spans : List.of();
    }

    private void emit(DayOfWeek day, TimeSlot slot, GroupColumns group, WeekType week, Integer subgroup,
                      CellContent content, String text, SourceLocation where, List<ParsedEntry> out) {
        try {
            out.add(new ParsedEntry(group.code(), day, slot.start(), slot.end(), week, subgroup, content.subject(),
                    content.lessonType(), content.teacherText(), content.roomText(), text, where));
        } catch (IllegalArgumentException e) {
            diagnostics.entrySkipped("INVALID_ENTRY", e.getMessage(), where, text);
            return;
        }
        diagnostics.entryRecognized();
        content.warnings().forEach(w -> diagnostics.warn(w.code(), w.message(), where, text));
        checkSubgroupMarker(content.subject(), subgroup, where, text);
    }

    /** Top row = numerator, bottom row = denominator, both = every week. {@code null} for slots of unexpected height. */
    private static WeekType weekType(TimeSlot slot, int r1, int r2) {
        if (slot.height() != EXPECTED_SLOT_HEIGHT) {
            return null;
        }
        boolean top = r1 == slot.firstRow();
        boolean bottom = r2 == slot.lastRow();
        if (top && bottom) {
            return WeekType.EVERY_WEEK;
        }
        return top ? WeekType.NUMERATOR : WeekType.DENOMINATOR;
    }

    /** The subject sometimes repeats the subgroup ("... - 2 п/г"); it must agree with the geometry. */
    private void checkSubgroupMarker(String subject, Integer subgroup, SourceLocation where, String raw) {
        Matcher m = SUBGROUP_IN_TEXT.matcher(subject);
        if (m.find() && (subgroup == null || Integer.parseInt(m.group(1)) != subgroup)) {
            diagnostics.warn("SUBGROUP_MISMATCH", "Text says subgroup " + m.group(1) + " but the cell position says "
                    + (subgroup == null ? "whole group" : "subgroup " + subgroup), where, raw);
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    private String text(int row, int col) {
        Row r = sheet.getRow(row);
        if (r == null) {
            return "";
        }
        Cell cell = r.getCell(col);
        return cell == null ? "" : formatter.formatCellValue(cell);
    }

    private static String normalize(String dayName) {
        return dayName.strip().toUpperCase(Locale.ROOT);
    }

    private SourceLocation location(int row, int col) {
        return new SourceLocation(sheet.getSheetName(), new CellReference(row, col).formatAsString());
    }

    private SourceLocation location(CellRangeAddress range, int row, int col) {
        return range != null
                ? new SourceLocation(sheet.getSheetName(), range.formatAsString())
                : location(row, col);
    }
}
