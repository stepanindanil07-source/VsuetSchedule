package ru.vsuet.schedule.importer.parser.fuits;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Builds tiny FUITS-like workbooks for tests. Layout of every sheet (Excel rows, 1-based):
 * <pre>
 *   row 1  : semester text in column C
 *   row 2  : "Дни" | "Часы" | group codes from column C (a group with span N is merged over N columns)
 *   row 3+ : day blocks; each slot has a given height; day and time cells are merged over their rows
 * </pre>
 * Lesson cells are written with {@link #put} and {@link #merge} using plain Excel references, so a test
 * can say exactly which geometry it checks.
 */
public final class WorkbookFixture {

    private record Slot(String time, int height) {
    }

    private static final class SheetState {
        final Sheet sheet;
        final List<String> dayNames = new ArrayList<>();
        final List<List<Slot>> slots = new ArrayList<>();

        SheetState(Sheet sheet) {
            this.sheet = sheet;
        }
    }

    private final Workbook workbook;
    private final List<SheetState> sheets = new ArrayList<>();
    private SheetState current;

    private WorkbookFixture(Workbook workbook) {
        this.workbook = workbook;
    }

    public static WorkbookFixture xlsx() {
        return new WorkbookFixture(new XSSFWorkbook());
    }

    public static WorkbookFixture xls() {
        return new WorkbookFixture(new HSSFWorkbook());
    }

    /**
     * Starts a sheet with a header.
     *
     * @param semesterText text for the semester cell, or {@code null} for none
     * @param groups       alternating group code (String) and column span (Integer)
     */
    public WorkbookFixture sheet(String name, String semesterText, Object... groups) {
        startSheet(name);
        if (semesterText != null) {
            cell(0, 2).setCellValue(semesterText);
        }
        cell(1, 0).setCellValue("Дни");
        cell(1, 1).setCellValue("Часы");
        int col = 2;
        for (int i = 0; i < groups.length; i += 2) {
            int span = (Integer) groups[i + 1];
            cell(1, col).setCellValue((String) groups[i]);
            mergeIfMany(1, 1, col, col + span - 1);
            col += span;
        }
        return this;
    }

    /** A sheet that has some text but no "Дни" header. */
    public WorkbookFixture sheetWithoutHeader(String name) {
        startSheet(name);
        cell(0, 0).setCellValue("something else entirely");
        return this;
    }

    public WorkbookFixture emptySheet(String name) {
        startSheet(name);
        return this;
    }

    public WorkbookFixture day(String name) {
        current.dayNames.add(name);
        current.slots.add(new ArrayList<>());
        return this;
    }

    public WorkbookFixture slot(String time, int height) {
        current.slots.getLast().add(new Slot(time, height));
        return this;
    }

    public WorkbookFixture put(String ref, String text) {
        CellReference r = new CellReference(ref);
        cell(r.getRow(), r.getCol()).setCellValue(text);
        return this;
    }

    public WorkbookFixture put(String ref, double number) {
        CellReference r = new CellReference(ref);
        cell(r.getRow(), r.getCol()).setCellValue(number);
        return this;
    }

    public WorkbookFixture merge(String range) {
        current.sheet.addMergedRegion(CellRangeAddress.valueOf(range));
        return this;
    }

    public InputStream stream() {
        for (SheetState s : sheets) {
            writeLayout(s);
        }
        try (Workbook wb = workbook; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return new ByteArrayInputStream(out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void startSheet(String name) {
        current = new SheetState(workbook.createSheet(name));
        sheets.add(current);
    }

    private void writeLayout(SheetState s) {
        current = s;
        int row = 2;
        for (int d = 0; d < s.dayNames.size(); d++) {
            int dayStart = row;
            for (Slot slot : s.slots.get(d)) {
                cell(row, 1).setCellValue(slot.time());
                mergeIfMany(row, row + slot.height() - 1, 1, 1);
                row += slot.height();
            }
            cell(dayStart, 0).setCellValue(s.dayNames.get(d));
            mergeIfMany(dayStart, row - 1, 0, 0);
        }
    }

    private void mergeIfMany(int firstRow, int lastRow, int firstCol, int lastCol) {
        if (firstRow != lastRow || firstCol != lastCol) {
            current.sheet.addMergedRegion(new CellRangeAddress(firstRow, lastRow, firstCol, lastCol));
        }
    }

    private Cell cell(int row, int col) {
        Row r = current.sheet.getRow(row);
        if (r == null) {
            r = current.sheet.createRow(row);
        }
        Cell c = r.getCell(col);
        return c != null ? c : r.createCell(col);
    }
}
