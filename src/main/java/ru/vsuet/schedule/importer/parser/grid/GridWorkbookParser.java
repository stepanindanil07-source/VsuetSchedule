package ru.vsuet.schedule.importer.parser.grid;

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
 * Workbook-level logic shared by all VSUET grid formats: open the file (xlsx or xls), parse every schedule sheet
 * with {@link GridSheetParser}, find the semester, merge the sheets and decide when the file is too different from
 * what is expected to be trusted. A concrete parser only supplies its key, version and {@link GridFormat}.
 *
 * <p>Fatal (the whole import must be rejected): unreadable file, a non-empty sheet without the expected header,
 * a semester that two sheets disagree about, duplicate group codes, no lessons at all, and (when
 * {@code periodRequired}) no semester anywhere. Without a stated semester the result has no period and the import
 * must pin the source to one.
 */
public abstract class GridWorkbookParser implements ScheduleParser {

    private static final Pattern SEMESTER = Pattern.compile(
            "(весенн\\p{L}*|осенн\\p{L}*)\\s+семестр\\p{L}*\\s+(\\d{4})\\s*[-–]\\s*(\\d{4})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final String key;
    private final String version;
    private final GridFormat format;
    private final boolean periodRequired;

    protected GridWorkbookParser(String key, String version, GridFormat format, boolean periodRequired) {
        this.key = key;
        this.version = version;
        this.format = format;
        this.periodRequired = periodRequired;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public String version() {
        return version;
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
            int headerRow = GridSheetParser.findHeaderRow(sheet);
            if (headerRow < 0) {
                if (isEmpty(sheet)) {
                    diagnostics.warn("EMPTY_SHEET_IGNORED", "Sheet is empty",
                            new SourceLocation(sheet.getSheetName(), "-"), null);
                    continue;
                }
                throw fail("Sheet '" + sheet.getSheetName() + "' has no 'Дни' header: unknown layout", diagnostics, null);
            }
            findPeriod(sheet, headerRow, diagnostics).ifPresent(periods::add);

            GridSheetParser.SheetResult result = new GridSheetParser(format, diagnostics).parse(sheet, headerRow);
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
        if (periods.isEmpty() && periodRequired) {
            throw fail("Semester (e.g. 'весеннего семестра 2025-2026') not found on any sheet", diagnostics, null);
        }
        if (periods.size() > 1) {
            throw fail("Sheets disagree about the semester: " + periods, diagnostics, null);
        }
        if (entries.isEmpty()) {
            throw fail("No lessons found in the workbook", diagnostics, null);
        }
        ParsedPeriod period = periods.isEmpty() ? null : periods.iterator().next();
        return new ParsedSchedule(key, period, groups, entries, diagnostics.build(version));
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

    private ScheduleParseException fail(String message, DiagnosticsCollector diagnostics, Throwable cause) {
        return cause == null
                ? new ScheduleParseException(message, diagnostics.build(version))
                : new ScheduleParseException(message, diagnostics.build(version), cause);
    }
}
