package ru.vsuet.schedule.importer.parser.fuits;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import ru.vsuet.schedule.academic.domain.AcademicTerm;
import ru.vsuet.schedule.importer.parser.DiagnosticsCollector;
import ru.vsuet.schedule.importer.parser.ParsedEntry;
import ru.vsuet.schedule.importer.parser.ParsedGroup;
import ru.vsuet.schedule.importer.parser.ParsedPeriod;
import ru.vsuet.schedule.importer.parser.ParsedSchedule;
import ru.vsuet.schedule.importer.parser.ScheduleParseException;
import ru.vsuet.schedule.importer.parser.ScheduleParser;
import ru.vsuet.schedule.importer.parser.SourceLocation;

/**
 * Parser for the VSUET grid workbooks (uits, itf, eht, tf, eiu .xlsx): one sheet per course, groups across,
 * days and two-row time slots down. All faculties checked so far share this layout, so one parser serves them all;
 * a faculty with a different layout gets its own parser and its own key. Sheet-level work is in {@link FuitsSheetParser}; this class opens the workbook,
 * finds the semester, merges the sheets and decides when the file is too different to trust.
 *
 * <p>Fatal (whole import must be rejected): unreadable file, a non-empty sheet without the expected
 * header, no semester found or two different semesters, duplicate group codes, no lessons at all.
 */
@Component
public class FuitsExcelParser implements ScheduleParser {

    public static final String KEY = "vsuet-grid-xlsx";
    static final String VERSION = "1";

    private static final Pattern SEMESTER = Pattern.compile(
            "(весенн\\p{L}*|осенн\\p{L}*)\\s+семестр\\p{L}*\\s+(\\d{4})\\s*[-–]\\s*(\\d{4})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

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
        DiagnosticsCollector diagnostics = new DiagnosticsCollector();
        try (Workbook workbook = open(file, diagnostics)) {
            return parseWorkbook(workbook, diagnostics);
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

    private ParsedSchedule parseWorkbook(Workbook workbook, DiagnosticsCollector diagnostics) {
        List<ParsedGroup> groups = new ArrayList<>();
        List<ParsedEntry> entries = new ArrayList<>();
        Set<ParsedPeriod> periods = new LinkedHashSet<>();
        Set<String> groupCodes = new HashSet<>();
        int parsedSheets = 0;

        for (Sheet sheet : workbook) {
            int headerRow = FuitsSheetParser.findHeaderRow(sheet);
            if (headerRow < 0) {
                if (isEmpty(sheet)) {
                    diagnostics.warn("EMPTY_SHEET_IGNORED", "Sheet is empty",
                            new SourceLocation(sheet.getSheetName(), "-"), null);
                    continue;
                }
                throw fail("Sheet '" + sheet.getSheetName() + "' has no 'Дни' header: unknown layout", diagnostics, null);
            }
            findPeriod(sheet, headerRow, diagnostics).ifPresent(periods::add);

            FuitsSheetParser.SheetResult result = new FuitsSheetParser(diagnostics).parse(sheet, headerRow);
            if (result == null) {
                throw fail("Sheet '" + sheet.getSheetName() + "' has an unexpected header structure", diagnostics, null);
            }
            for (ParsedGroup group : result.groups()) {
                if (!groupCodes.add(group.code())) {
                    throw fail("Group '" + group.code() + "' appears more than once (sheet '"
                            + sheet.getSheetName() + "')", diagnostics, null);
                }
            }
            groups.addAll(result.groups());
            entries.addAll(result.entries());
            parsedSheets++;
        }

        if (parsedSheets == 0) {
            throw fail("The workbook contains no schedule sheets", diagnostics, null);
        }
        if (periods.isEmpty()) {
            throw fail("Semester (e.g. 'весеннего семестра 2025-2026') not found on any sheet", diagnostics, null);
        }
        if (periods.size() > 1) {
            throw fail("Sheets disagree about the semester: " + periods, diagnostics, null);
        }
        if (entries.isEmpty()) {
            throw fail("No lessons found in the workbook", diagnostics, null);
        }
        return new ParsedSchedule(KEY, periods.iterator().next(), groups, entries, diagnostics.build(VERSION));
    }

    /** Looks for the semester text in the rows above the header; sheets without it are fine (e.g. masters sheet). */
    private Optional<ParsedPeriod> findPeriod(Sheet sheet, int headerRow, DiagnosticsCollector diagnostics) {
        DataFormatter formatter = new DataFormatter();
        for (int r = sheet.getFirstRowNum(); r < headerRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            for (Cell cell : row) {
                Matcher m = SEMESTER.matcher(formatter.formatCellValue(cell));
                if (m.find()) {
                    AcademicTerm term = m.group(1).toLowerCase(Locale.ROOT).startsWith("весенн")
                            ? AcademicTerm.SPRING : AcademicTerm.AUTUMN;
                    try {
                        return Optional.of(new ParsedPeriod(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), term));
                    } catch (IllegalArgumentException e) {
                        throw fail("Invalid semester '" + m.group() + "': " + e.getMessage(), diagnostics, e);
                    }
                }
            }
        }
        return Optional.empty();
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

    private static ScheduleParseException fail(String message, DiagnosticsCollector diagnostics, Throwable cause) {
        return cause == null
                ? new ScheduleParseException(message, diagnostics.build(VERSION))
                : new ScheduleParseException(message, diagnostics.build(VERSION), cause);
    }
}
