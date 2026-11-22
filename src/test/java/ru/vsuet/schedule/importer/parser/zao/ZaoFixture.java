package ru.vsuet.schedule.importer.parser.zao;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Tiny correspondence-schedule workbooks (Excel rows 1-based):
 * <pre>
 *   row 1  : "Дни" (A) | "Часы" (B) | group codes, each merged over three columns [text, hidden middle, room]
 *   row 2+ : day blocks; a day label (A) merged over its rows, slots (B) merged over two rows each
 * </pre>
 * Group {@code g} (0-based) has its text in column {@code 3 + 3g} (C, F, I...), the hidden middle one right of it and
 * its rooms in {@code 5 + 3g} (E, H, K...) when counting columns from 1.
 */
public final class ZaoFixture {

    private final Workbook workbook = new XSSFWorkbook();
    private final Sheet sheet;
    private final int groupCount;
    private int nextRow = 1;   // zero-based index of the next free row
    private final List<String> dayLabels = new ArrayList<>();
    private final List<Integer> dayFirstRows = new ArrayList<>();

    private ZaoFixture(String... groups) {
        sheet = workbook.createSheet("Список 8");
        groupCount = groups.length;
        cell(0, 0).setCellValue("Дни");
        cell(0, 1).setCellValue("Часы");
        for (int g = 0; g < groups.length; g++) {
            int first = 2 + 3 * g;
            cell(0, first).setCellValue(groups[g]);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, first, first + 2));
            sheet.setColumnHidden(first + 1, true);
        }
    }

    public static ZaoFixture withGroups(String... groups) {
        return new ZaoFixture(groups);
    }

    /** Adds a day block with one two-row slot per time ("08.00-09.35"). */
    public ZaoFixture day(String label, String... times) {
        int first = nextRow;
        for (String time : times) {
            cell(nextRow, 1).setCellValue(time);
            sheet.addMergedRegion(new CellRangeAddress(nextRow, nextRow + 1, 1, 1));
            nextRow += 2;
        }
        cell(first, 0).setCellValue(label);
        sheet.addMergedRegion(new CellRangeAddress(first, nextRow - 1, 0, 0));
        dayLabels.add(label);
        dayFirstRows.add(first);
        return this;
    }

    /** A lesson in slot {@code slot} (0-based) of the day block labelled {@code dayLabel}, for group {@code group}. */
    public ZaoFixture lesson(String dayLabel, int slot, int group, String subject, String teacher, String room) {
        return lesson(dayLabel, slot, group, subject, teacher, room, null);
    }

    public ZaoFixture lesson(String dayLabel, int slot, int group, String subject, String teacher, String room,
                      String secondRoom) {
        int top = dayFirstRows.get(dayLabels.indexOf(dayLabel)) + 2 * slot;
        int text = 2 + 3 * group;
        if (subject != null) {
            cell(top, text).setCellValue(subject);
        }
        if (teacher != null) {
            cell(top + 1, text).setCellValue(teacher);
        }
        if (room != null) {
            cell(top, text + 2).setCellValue(room);
        }
        if (secondRoom != null) {
            cell(top + 1, text + 2).setCellValue(secondRoom);
        }
        return this;
    }

    ZaoFixture put(String ref, String text) {
        CellReference r = new CellReference(ref);
        cell(r.getRow(), r.getCol()).setCellValue(text);
        return this;
    }

    ZaoFixture hideColumn(String column) {
        sheet.setColumnHidden(CellReference.convertColStringToIndex(column), true);
        return this;
    }

    ZaoFixture showColumn(String column) {
        sheet.setColumnHidden(CellReference.convertColStringToIndex(column), false);
        return this;
    }

    ZaoFixture hideRow(int excelRow) {
        Row row = sheet.getRow(excelRow - 1);
        if (row == null) {
            row = sheet.createRow(excelRow - 1);
        }
        row.setZeroHeight(true);
        return this;
    }

    int groups() {
        return groupCount;
    }

    public InputStream stream() {
        try (Workbook wb = workbook; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return new ByteArrayInputStream(out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Cell cell(int row, int col) {
        Row r = sheet.getRow(row);
        if (r == null) {
            r = sheet.createRow(row);
        }
        Cell c = r.getCell(col);
        return c != null ? c : r.createCell(col);
    }
}
