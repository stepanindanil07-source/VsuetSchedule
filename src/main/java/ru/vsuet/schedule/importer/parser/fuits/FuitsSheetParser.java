package ru.vsuet.schedule.importer.parser.fuits;

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
import ru.vsuet.schedule.importer.parser.DiagnosticsCollector;
import ru.vsuet.schedule.importer.parser.ParsedEntry;
import ru.vsuet.schedule.importer.parser.ParsedGroup;
import ru.vsuet.schedule.importer.parser.SourceLocation;
import ru.vsuet.schedule.importer.parser.fuits.CellTextParser.CellContent;
import ru.vsuet.schedule.importer.parser.fuits.CellTextParser.Rejected;
import ru.vsuet.schedule.importer.parser.fuits.CellTextParser.Result;
import ru.vsuet.schedule.common.WeekType;

/**
 * Parses one FUITS sheet. Nothing here relies on fixed row or column numbers: the header row is found by
 * its "Дни" cell, groups and their column spans come from the header's merged ranges, days and time slots
 * come from merged ranges in the first two columns, and the week/subgroup of a lesson is derived from the
 * geometry of its merged range inside the slot x group rectangle.
 *
 * <pre>
 *   slot (2 rows)         group with 2 columns
 *   top row    = numerator      left column  = subgroup 1
 *   bottom row = denominator    right column = subgroup 2
 * </pre>
 * Merged range covers both rows -> every week; covers all group columns -> whole group.
 * One instance per parse run (it holds a stateful {@link DataFormatter}).
 */
final class FuitsSheetParser {

    private static final String DAYS_HEADER = "Дни";
    private static final String TIME_HEADER = "Часы";
    private static final int HEADER_SEARCH_LIMIT = 40;
    private static final int EXPECTED_SLOT_HEIGHT = 2;

    private static final Map<String, DayOfWeek> DAYS = Map.of(
            "ПОНЕДЕЛЬНИК", DayOfWeek.MONDAY, "ВТОРНИК", DayOfWeek.TUESDAY, "СРЕДА", DayOfWeek.WEDNESDAY,
            "ЧЕТВЕРГ", DayOfWeek.THURSDAY, "ПЯТНИЦА", DayOfWeek.FRIDAY, "СУББОТА", DayOfWeek.SATURDAY,
            "ВОСКРЕСЕНЬЕ", DayOfWeek.SUNDAY);
    private static final Pattern TIME_RANGE = Pattern.compile("(\\d{1,2})[.:](\\d{2})\\s*[-–]\\s*(\\d{1,2})[.:](\\d{2})");
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

    record SheetResult(List<ParsedGroup> groups, List<ParsedEntry> entries) {
    }

    private final DataFormatter formatter = new DataFormatter();
    private final CellTextParser cellTextParser = new CellTextParser();
    private final DiagnosticsCollector diagnostics;

    private Sheet sheet;
    private MergedRegions merged;
    private List<GroupColumns> allGroups = List.of();

    FuitsSheetParser(DiagnosticsCollector diagnostics) {
        this.diagnostics = diagnostics;
    }

    /** Row index (zero-based) of the header row, or -1 if the sheet has no "Дни" cell. */
    static int findHeaderRow(Sheet sheet) {
        DataFormatter fmt = new DataFormatter();
        int limit = Math.min(sheet.getLastRowNum(), HEADER_SEARCH_LIMIT);
        for (int r = sheet.getFirstRowNum(); r <= limit; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            for (Cell cell : row) {
                if (fmt.formatCellValue(cell).strip().equalsIgnoreCase(DAYS_HEADER)) {
                    return r;
                }
            }
        }
        return -1;
    }

    /** @return the parsed sheet, or {@code null} if the header has no "Часы" column or no groups (reported as an error). */
    SheetResult parse(Sheet sheet, int headerRow) {
        this.sheet = sheet;
        this.merged = new MergedRegions(sheet);

        int dayCol = findColumn(headerRow, DAYS_HEADER);
        int timeCol = findColumn(headerRow, TIME_HEADER);
        if (timeCol < 0) {
            diagnostics.warn("SHEET_NO_TIME_COLUMN", "Header row has no '" + TIME_HEADER + "' cell",
                    location(headerRow, dayCol), null);
            return null;
        }
        List<GroupColumns> groups = readGroups(headerRow, timeCol);
        this.allGroups = groups;
        if (groups.isEmpty()) {
            diagnostics.warn("SHEET_NO_GROUPS", "No group codes found right of '" + TIME_HEADER + "'",
                    location(headerRow, timeCol), null);
            return null;
        }

        List<ParsedEntry> entries = new ArrayList<>();
        for (int[] block : findDayBlocks(headerRow, dayCol)) {
            DayOfWeek day = DAYS.get(normalize(text(block[0], dayCol)));
            for (TimeSlot slot : findSlots(block[0], block[1], timeCol)) {
                for (GroupColumns group : groups) {
                    parseGroupInSlot(day, slot, group, entries);
                }
            }
        }
        return new SheetResult(groups.stream().map(g -> new ParsedGroup(g.code())).toList(), entries);
    }

    // ---- structure ---------------------------------------------------------------------------------------

    private int findColumn(int row, String header) {
        Row r = sheet.getRow(row);
        for (Cell cell : r) {
            if (formatter.formatCellValue(cell).strip().equalsIgnoreCase(header)) {
                return cell.getColumnIndex();
            }
        }
        return -1;
    }

    private List<GroupColumns> readGroups(int headerRow, int timeCol) {
        List<GroupColumns> groups = new ArrayList<>();
        Row row = sheet.getRow(headerRow);
        for (Cell cell : row) {
            int col = cell.getColumnIndex();
            String code = formatter.formatCellValue(cell).strip();
            if (col <= timeCol || code.isEmpty()) {
                continue;
            }
            CellRangeAddress range = merged.at(headerRow, col);
            int last = range != null ? range.getLastColumn() : col;
            groups.add(new GroupColumns(code, col, last));
        }
        return groups;
    }

    /** Rows {@code [first, last]} of every recognised day block; unknown day names are reported and skipped. */
    private List<int[]> findDayBlocks(int headerRow, int dayCol) {
        List<int[]> blocks = new ArrayList<>();
        int lastRow = sheet.getLastRowNum();
        for (int r = headerRow + 1; r <= lastRow; r++) {
            String name = text(r, dayCol);
            if (name.isBlank()) {
                continue;
            }
            CellRangeAddress range = merged.at(r, dayCol);
            int end = range != null ? range.getLastRow() : r;
            if (DAYS.containsKey(normalize(name))) {
                blocks.add(new int[] {r, end});
            } else {
                diagnostics.warn("UNKNOWN_DAY", "Unrecognised day name '" + name.strip() + "'; its rows were not read",
                        location(r, dayCol), name);
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
            Matcher m = TIME_RANGE.matcher(time);
            if (m.find()) {
                LocalTime start = LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
                LocalTime stop = LocalTime.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)));
                slots.add(new TimeSlot(r, end, start, stop));
            } else {
                diagnostics.warn("UNPARSEABLE_TIME", "Cannot read time interval '" + time.strip() + "'; its rows were not read",
                        location(r, timeCol), time);
            }
            r = end;
        }
        return slots;
    }

    // ---- lessons -----------------------------------------------------------------------------------------

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
        Result result = cellTextParser.parse(text);
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

        // A lesson shared by several groups (a stream) may be one cell merged over whole neighbouring groups.
        // It is then repeated for every covered group, always for the whole group.
        if (c2 > group.lastCol()) {
            List<GroupColumns> covered = wholeGroupsSpanning(c1, c2);
            if (covered.isEmpty()) {
                diagnostics.entrySkipped("MERGE_CROSSES_BOUNDARY",
                        "Merged range cuts through a group instead of covering whole groups", where, text);
                return;
            }
            for (GroupColumns g : covered) {
                emit(day, slot, g, week, null, content, text, where, out);
            }
            return;
        }

        Integer subgroup;
        if (c1 == group.firstCol() && c2 == group.lastCol()) {
            subgroup = null;
        } else if (c1 == c2) {
            subgroup = c1 - group.firstCol() + 1;
        } else {
            diagnostics.entrySkipped("PARTIAL_GROUP_SPAN",
                    "Lesson covers some but not all of " + group.width() + " subgroup columns", where, text);
            return;
        }
        emit(day, slot, group, week, subgroup, content, text, where, out);
    }

    /** Neighbouring groups that exactly fill columns {@code c1..c2}, or an empty list if the range cuts a group. */
    private List<GroupColumns> wholeGroupsSpanning(int c1, int c2) {
        List<GroupColumns> covered = new ArrayList<>();
        int next = c1;
        for (GroupColumns g : allGroups) {
            if (g.firstCol() == next && g.lastCol() <= c2) {
                covered.add(g);
                next = g.lastCol() + 1;
            }
        }
        return next == c2 + 1 ? covered : List.of();
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
