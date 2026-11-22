package ru.vsuet.schedule.importer.parser.zao;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import ru.vsuet.schedule.importer.parser.DiagnosticsCollector;
import ru.vsuet.schedule.importer.parser.ParseContext;
import ru.vsuet.schedule.importer.parser.ParsedEntry;
import ru.vsuet.schedule.importer.parser.ParsedGroup;
import ru.vsuet.schedule.importer.parser.ParsedSchedule;
import ru.vsuet.schedule.importer.parser.ScheduleParseException;
import ru.vsuet.schedule.importer.parser.ScheduleParser;
import ru.vsuet.schedule.importer.parser.SourceLocation;

/**
 * Parser of the correspondence-course (заочное обучение) schedule, {@code zao/5_kurs.xlsx}: lessons are listed per
 * calendar date, not per weekday and week parity, so every entry carries a {@link ParsedEntry#date() date}.
 * See {@link ZaoSheetParser} for the layout. It is independent of the weekly grid parsers on purpose: it shares
 * nothing with them but the canonical model.
 *
 * <p>The file states neither the semester nor the course, and writes dates without a year: the source must be pinned
 * to an academic period (the importer passes its first day in the {@link ParseContext}).
 */
@Component
public class ZaoExcelParser implements ScheduleParser {

    public static final String KEY = "zao-xlsx";
    static final String VERSION = "1";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public ParsedSchedule parse(InputStream file) {
        return parse(file, ParseContext.unknown());
    }

    @Override
    public ParsedSchedule parse(InputStream file, ParseContext context) {
        DiagnosticsCollector diagnostics = new DiagnosticsCollector();
        if (context.periodStart() == null) {
            throw fail("The correspondence schedule writes dates without a year; pin the source to an academic period "
                    + "(schedule_source.academic_period_id) so the year can be derived", diagnostics, null);
        }
        try (Workbook workbook = open(file, diagnostics)) {
            return parseWorkbook(workbook, context, diagnostics);
        } catch (IOException e) {
            throw fail("Cannot close workbook: " + e.getMessage(), diagnostics, e);
        }
    }

    private Workbook open(InputStream file, DiagnosticsCollector diagnostics) {
        try {
            return WorkbookFactory.create(file);
        } catch (IOException | RuntimeException e) {
            throw fail("Cannot read the file as an Excel workbook: " + e.getMessage(), diagnostics, e);
        }
    }

    private ParsedSchedule parseWorkbook(Workbook workbook, ParseContext context, DiagnosticsCollector diagnostics) {
        List<ParsedGroup> groups = new ArrayList<>();
        List<ParsedEntry> entries = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        int parsedSheets = 0;

        for (Sheet sheet : workbook) {
            int headerRow = ZaoSheetParser.findHeaderRow(sheet);
            if (headerRow < 0) {
                if (isEmpty(sheet)) {
                    diagnostics.warn("EMPTY_SHEET_IGNORED", "Sheet is empty", new SourceLocation(sheet.getSheetName(), "-"), null);
                    continue;
                }
                throw fail("Sheet '" + sheet.getSheetName() + "' has no 'Дни' header: unknown layout", diagnostics, null);
            }
            ZaoSheetParser.SheetResult result = new ZaoSheetParser(diagnostics, context.periodStart()).parse(sheet, headerRow);
            if (result == null) {
                throw fail("Sheet '" + sheet.getSheetName() + "' has an unexpected header structure", diagnostics, null);
            }
            for (ParsedGroup group : result.groups()) {
                if (!codes.add(group.code())) {
                    throw fail("Group '" + group.code() + "' appears more than once (sheet '" + sheet.getSheetName() + "')",
                            diagnostics, null);
                }
            }
            groups.addAll(result.groups());
            entries.addAll(result.entries());
            parsedSheets++;
        }
        if (parsedSheets == 0) {
            throw fail("The workbook contains no schedule sheets", diagnostics, null);
        }
        if (entries.isEmpty()) {
            throw fail("No lessons found in the workbook", diagnostics, null);
        }
        return new ParsedSchedule(KEY, null, groups, entries, diagnostics.build(VERSION));
    }

    private static boolean isEmpty(Sheet sheet) {
        DataFormatter formatter = new DataFormatter();
        for (Row row : sheet) {
            for (Cell cell : row) {
                if (!formatter.formatCellValue(cell).isBlank()) {
                    return false;
                }
            }
        }
        return true;
    }

    private ScheduleParseException fail(String message, DiagnosticsCollector diagnostics, Throwable cause) {
        return cause == null
                ? new ScheduleParseException(message, diagnostics.build(VERSION))
                : new ScheduleParseException(message, diagnostics.build(VERSION), cause);
    }
}
