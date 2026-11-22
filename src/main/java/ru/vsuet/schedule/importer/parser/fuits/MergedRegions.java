package ru.vsuet.schedule.importer.parser.fuits;

import java.util.HashMap;
import java.util.Map;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;

/** Index "cell -> merged range that covers it" for one sheet, so a lookup does not scan all ranges. */
final class MergedRegions {

    private final Map<Long, CellRangeAddress> byCell = new HashMap<>();

    MergedRegions(Sheet sheet) {
        for (CellRangeAddress range : sheet.getMergedRegions()) {
            for (int row = range.getFirstRow(); row <= range.getLastRow(); row++) {
                for (int col = range.getFirstColumn(); col <= range.getLastColumn(); col++) {
                    byCell.put(key(row, col), range);
                }
            }
        }
    }

    /** The merged range covering the cell, or {@code null} if the cell is not merged. */
    CellRangeAddress at(int row, int col) {
        return byCell.get(key(row, col));
    }

    /** True for the top-left cell of a merged range (the only one that carries the value). */
    static boolean isAnchor(CellRangeAddress range, int row, int col) {
        return range.getFirstRow() == row && range.getFirstColumn() == col;
    }

    private static long key(int row, int col) {
        return ((long) row << 20) | col;
    }
}
