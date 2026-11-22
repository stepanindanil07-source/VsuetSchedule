package ru.vsuet.schedule.importer.parser.zao;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

/**
 * One sheet of the correspondence (zaochnoe) schedule. Unlike the weekly grid of the other faculties:
 * <pre>
 *   column A   a calendar day, merged over its rows:  "ПНД \n12/10"  (weekday + day/month, no year)
 *   column B   time slots, each merged over TWO rows: "08.00-09.35"
 *   per group  three columns (the middle one is hidden): [text] [hidden] [room]
 *                row 1 of a slot:  "тип. предмет"   room
 *                row 2 of a slot:  преподаватель     (second room)
 * </pre>
 * There is no numerator/denominator and nothing is merged across groups: every group repeats its own lessons.
 * The year of "12/10" is not in the file, it comes from the academic period the source is pinned to; the weekday
 * written next to the date is checked against it, so a wrong pin cannot go unnoticed.
 * Hidden columns and rows are not part of the schedule (as in the SPO files). Text that fits none of the four
 * positions is never dropped silently: inside a group it is a skipped lesson, outside every group it is counted
 * in one warning. One instance per parse run.
 */
final class ZaoSheetParser {

    private static final Pattern DAYS_LABEL = Pattern.compile("(?iu)^\\s*дни\\s*$");
    private static final Pattern TIME_LABEL = Pattern.compile("(?iu)^\\s*часы\\s*$");
    private static final Pattern DAY_AND_DATE = Pattern.compile("^\\s*(\\p{L}{3})\\.?\\s+(\\d{1,2})\\s*/\\s*(\\d{1,2})\\s*$");
    private static final Pattern TIME_RANGE = Pattern.compile("(\\d{1,2})[.: ](\\d{2})\\s*[-–]\\s*(\\d{1,2})[.: ](\\d{2})");
    private static final int HEADER_SEARCH_LIMIT = 100;
    private static final int SLOT_HEIGHT = 2;

    private static final Map<String, DayOfWeek> WEEKDAYS = Map.of(
            "ПНД", DayOfWeek.MONDAY, "ВТР", DayOfWeek.TUESDAY, "СРД", DayOfWeek.WEDNESDAY, "ЧТВ", DayOfWeek.THURSDAY,
            "ПТН", DayOfWeek.FRIDAY, "СБТ", DayOfWeek.SATURDAY, "ВСК", DayOfWeek.SUNDAY);

    /** Visible columns of a group: where its lesson text and its rooms are. {@code roomCol} is -1 with one column. */
    private record GroupColumns(String code, int textCol, int roomCol, List<Integer> columns) {
    }

    private record Slot(int firstRow, LocalTime start, LocalTime end) {
    }

    record SheetResult(List<ParsedGroup> groups, List<ParsedEntry> entries) {
    }

    private final DataFormatter formatter = new DataFormatter();
    private final DiagnosticsCollector diagnostics;
    private final LocalDate periodStart;

    private Sheet sheet;
    private final Map<Long, CellRangeAddress> merged = new HashMap<>();
    private final Set<Long> consumed = new HashSet<>();
    private int hiddenCells;
    private SourceLocation firstHiddenCell;
    private int outsideCells;
    private SourceLocation firstOutsideCell;

    ZaoSheetParser(DiagnosticsCollector diagnostics, LocalDate periodStart) {
        this.diagnostics = diagnostics;
        this.periodStart = periodStart;
    }

    /** Row index (zero-based) of the row with the "Дни" label, or -1. */
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

    /** @return the parsed sheet, or {@code null} if its header has no time column or no visible group (reported) */
    SheetResult parse(Sheet sheet, int headerRow) {
        this.sheet = sheet;
        for (CellRangeAddress range : sheet.getMergedRegions()) {
            for (int r = range.getFirstRow(); r <= range.getLastRow(); r++) {
                for (int c = range.getFirstColumn(); c <= range.getLastColumn(); c++) {
                    merged.put(key(r, c), range);
                }
            }
        }
        int dayCol = findLabel(headerRow, DAYS_LABEL);
        int timeCol = findLabel(headerRow, TIME_LABEL);
        if (timeCol < 0) {
            diagnostics.warn("SHEET_NO_TIME_COLUMN", "Header row has no time column label (Часы)",
                    location(headerRow, dayCol), null);
            return null;
        }
        List<String> hiddenGroups = new ArrayList<>();
        List<GroupColumns> groups = readGroups(headerRow, timeCol, hiddenGroups);
        if (groups.isEmpty()) {
            diagnostics.warn("SHEET_NO_GROUPS", "No visible group codes found right of the time column",
                    location(headerRow, timeCol), null);
            return null;
        }
        Set<Integer> groupColumns = new HashSet<>();
        groups.forEach(g -> groupColumns.addAll(g.columns()));

        List<ParsedEntry> entries = new ArrayList<>();
        for (int[] block : findDayBlocks(headerRow, dayCol)) {
            parseDay(block, dayCol, timeCol, groups, entries);
            reportUnplacedCells(block, timeCol, groupColumns);
        }
        if (!hiddenGroups.isEmpty()) {
            diagnostics.warn("HIDDEN_GROUPS_IGNORED", hiddenGroups.size() + " group(s) stand in columns hidden in Excel and "
                    + "were ignored: " + String.join(", ", hiddenGroups), location(headerRow, timeCol), null);
        }
        if (hiddenCells > 0) {
            diagnostics.warn("HIDDEN_CELLS_IGNORED", hiddenCells + " non-empty cell(s) in hidden columns or rows were ignored "
                    + "(Excel does not show them)", firstHiddenCell, null);
        }
        if (outsideCells > 0) {
            diagnostics.warn("CELLS_OUTSIDE_GROUPS_IGNORED", outsideCells + " non-empty cell(s) lie outside every group's "
                    + "columns and were ignored (leftovers such as a room without a lesson)", firstOutsideCell, null);
        }
        return new SheetResult(groups.stream().map(g -> new ParsedGroup(g.code())).toList(), entries);
    }

    // ---- structure ---------------------------------------------------------------------------------------

    private int findLabel(int row, Pattern label) {
        Row r = sheet.getRow(row);
        for (Cell cell : r) {
            if (label.matcher(formatter.formatCellValue(cell)).matches()) {
                return cell.getColumnIndex();
            }
        }
        return -1;
    }

    private List<GroupColumns> readGroups(int headerRow, int timeCol, List<String> hiddenGroups) {
        List<GroupColumns> groups = new ArrayList<>();
        Row row = sheet.getRow(headerRow);
        for (Cell cell : row) {
            int col = cell.getColumnIndex();
            String code = SPACES.matcher(formatter.formatCellValue(cell)).replaceAll(" ").strip();
            if (col <= timeCol || code.isEmpty()) {
                continue;
            }
            CellRangeAddress range = merged.get(key(headerRow, col));
            int last = range != null ? range.getLastColumn() : col;
            List<Integer> visible = new ArrayList<>();
            for (int c = col; c <= last; c++) {
                if (!sheet.isColumnHidden(c)) {
                    visible.add(c);
                }
            }
            if (visible.isEmpty()) {
                hiddenGroups.add(code);
            } else {
                groups.add(new GroupColumns(code, visible.getFirst(), visible.size() > 1 ? visible.getLast() : -1,
                        List.copyOf(visible)));
            }
        }
        return groups;
    }

    private static final Pattern SPACES = Pattern.compile("\\s+");

    /** Rows {@code [first, last]} of every day block (a merged cell in the day column). */
    private List<int[]> findDayBlocks(int headerRow, int dayCol) {
        List<int[]> blocks = new ArrayList<>();
        for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
            if (text(r, dayCol).isBlank()) {
                continue;
            }
            CellRangeAddress range = merged.get(key(r, dayCol));
            int end = range != null ? range.getLastRow() : r;
            blocks.add(new int[] {r, end});
            r = end;
        }
        return blocks;
    }

    // ---- one day -----------------------------------------------------------------------------------------

    private void parseDay(int[] block, int dayCol, int timeCol, List<GroupColumns> groups, List<ParsedEntry> out) {
        String label = text(block[0], dayCol);
        LocalDate date = readDate(label, block[0], dayCol);
        if (date == null) {
            return;   // reported; the block's cells end up in "unplaced"
        }
        for (Slot slot : findSlots(block, timeCol)) {
            for (GroupColumns group : groups) {
                parseLesson(date, slot, group, out);
            }
        }
    }

    /**
     * "ПНД \n12/10" -> the date. The year is the first one that puts the date on or after the period start; the weekday
     * written in the file must agree, otherwise the whole day is rejected (wrong pin or a typo: not guessable).
     */
    private LocalDate readDate(String label, int row, int col) {
        Matcher m = DAY_AND_DATE.matcher(label);
        DayOfWeek written = m.matches() ? WEEKDAYS.get(m.group(1).toUpperCase(java.util.Locale.ROOT)) : null;
        if (written == null) {
            diagnostics.warn("UNKNOWN_DAY", "Unrecognised day label '" + label.strip().replace('\n', ' ')
                    + "' (expected e.g. 'ПНД 12/10'); its rows were not read", location(row, col), label);
            return null;
        }
        LocalDate date;
        try {
            date = LocalDate.of(periodStart.getYear(), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)));
        } catch (DateTimeException e) {
            diagnostics.warn("INVALID_DATE", "'" + label.strip().replace('\n', ' ') + "' is not a calendar date; its rows "
                    + "were not read", location(row, col), label);
            return null;
        }
        if (date.isBefore(periodStart)) {
            date = date.plusYears(1);
        }
        if (date.getDayOfWeek() != written) {
            diagnostics.entrySkipped("DAY_WEEKDAY_MISMATCH", "The label says " + written + " but " + date + " is a "
                    + date.getDayOfWeek() + " (is the source pinned to the right semester?); the day was not read",
                    location(row, col), label);
            return null;
        }
        return date;
    }

    private List<Slot> findSlots(int[] block, int timeCol) {
        List<Slot> slots = new ArrayList<>();
        for (int r = block[0]; r <= block[1]; r++) {
            String time = text(r, timeCol);
            if (time.isBlank() || sheet.getRow(r) != null && sheet.getRow(r).getZeroHeight()) {
                continue;
            }
            CellRangeAddress range = merged.get(key(r, timeCol));
            int end = range != null ? range.getLastRow() : r;
            Matcher m = TIME_RANGE.matcher(time);
            if (!m.find()) {
                diagnostics.warn("UNPARSEABLE_TIME", "Cannot read time interval '" + time.strip() + "'; its rows were not read",
                        location(r, timeCol), time);
            } else if (end - r + 1 != SLOT_HEIGHT) {
                diagnostics.warn("UNEXPECTED_SLOT_HEIGHT", "Time slot spans " + (end - r + 1) + " rows instead of "
                        + SLOT_HEIGHT + " (subject row + teacher row); its rows were not read", location(r, timeCol), time);
            } else {
                slots.add(new Slot(r, LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))),
                        LocalTime.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)))));
            }
            r = end;
        }
        return slots;
    }

    private void parseLesson(LocalDate date, Slot slot, GroupColumns group, List<ParsedEntry> out) {
        int top = slot.firstRow();
        int bottom = top + 1;
        if (isRowHidden(top) || isRowHidden(bottom)) {
            return;   // counted as hidden cells by reportUnplacedCells
        }
        String subject = text(top, group.textCol());
        String teacher = text(bottom, group.textCol());
        String room1 = group.roomCol() < 0 ? "" : text(top, group.roomCol());
        String room2 = group.roomCol() < 0 ? "" : text(bottom, group.roomCol());
        consume(top, group.textCol());
        consume(bottom, group.textCol());
        if (group.roomCol() >= 0) {
            consume(top, group.roomCol());
            consume(bottom, group.roomCol());
        }
        if (subject.isBlank() && teacher.isBlank() && room1.isBlank() && room2.isBlank()) {
            return;
        }
        int lastCol = group.roomCol() >= 0 ? group.roomCol() : group.textCol();
        SourceLocation where = new SourceLocation(sheet.getSheetName(),
                new CellRangeAddress(top, bottom, group.textCol(), lastCol).formatAsString());
        String raw = rawText(subject, teacher, room1, room2);

        Object parsed = ZaoLessonText.parse(subject, teacher, room1, room2);
        if (parsed instanceof ZaoLessonText.Rejected rejected) {
            diagnostics.entrySkipped(rejected.code(), rejected.message(), where, raw);
            return;
        }
        ZaoLessonText.Lesson lesson = (ZaoLessonText.Lesson) parsed;
        try {
            out.add(new ParsedEntry(group.code(), date.getDayOfWeek(), slot.start(), slot.end(), WeekType.EVERY_WEEK,
                    lesson.subgroup(), lesson.subject(), lesson.type(), lesson.teacher(), lesson.room(), raw, where, date));
        } catch (IllegalArgumentException e) {
            diagnostics.entrySkipped("INVALID_ENTRY", e.getMessage(), where, raw);
            return;
        }
        diagnostics.entryRecognized();
        lesson.warnings().forEach(w -> diagnostics.warn(w.code(), w.message(), where, raw));
    }

    private static String rawText(String subject, String teacher, String room1, String room2) {
        List<String> lines = new ArrayList<>();
        for (String part : new String[] {subject.strip(), teacher.strip(), room1.strip(), room2.strip()}) {
            if (!part.isEmpty()) {
                lines.add(part);
            }
        }
        return String.join("\n", lines);
    }

    // ---- nothing silent ----------------------------------------------------------------------------------

    private void consume(int row, int col) {
        consumed.add(key(row, col));
    }

    /**
     * Every non-empty cell of the block that was not read as part of a lesson is accounted for: hidden ones are counted,
     * text inside a group's columns becomes a skipped lesson (a slot of unexpected shape, text in the hidden middle
     * column's neighbour...), text outside all groups is counted in one warning.
     */
    private void reportUnplacedCells(int[] block, int timeCol, Set<Integer> groupColumns) {
        for (int r = block[0]; r <= block[1]; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            for (Cell cell : row) {
                int c = cell.getColumnIndex();
                String text = formatter.formatCellValue(cell);
                if (c <= timeCol || text.isBlank() || consumed.contains(key(r, c))) {
                    continue;
                }
                CellRangeAddress range = merged.get(key(r, c));
                if (range != null && !(range.getFirstRow() == r && range.getFirstColumn() == c)) {
                    continue;
                }
                SourceLocation where = range != null
                        ? new SourceLocation(sheet.getSheetName(), range.formatAsString()) : location(r, c);
                if (row.getZeroHeight() || sheet.isColumnHidden(c)) {
                    hiddenCells++;
                    if (firstHiddenCell == null) {
                        firstHiddenCell = where;
                    }
                } else if (groupColumns.contains(c)) {
                    diagnostics.entrySkipped("UNPLACED_TEXT", "Text where no lesson is expected: not in a valid time slot or "
                            + "not in a group's text/room column", where, text);
                } else {
                    outsideCells++;
                    if (firstOutsideCell == null) {
                        firstOutsideCell = where;
                    }
                }
            }
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    private boolean isRowHidden(int rowIndex) {
        Row row = sheet.getRow(rowIndex);
        return row != null && row.getZeroHeight();
    }

    private String text(int row, int col) {
        Row r = sheet.getRow(row);
        if (r == null) {
            return "";
        }
        Cell cell = r.getCell(col);
        return cell == null ? "" : formatter.formatCellValue(cell);
    }

    private SourceLocation location(int row, int col) {
        return new SourceLocation(sheet.getSheetName(), new CellReference(row, Math.max(col, 0)).formatAsString());
    }

    private static long key(int row, int col) {
        return ((long) row << 20) | col;
    }
}
