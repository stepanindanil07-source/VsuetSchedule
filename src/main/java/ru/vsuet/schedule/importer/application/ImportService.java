package ru.vsuet.schedule.importer.application;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.vsuet.schedule.common.Hashes;
import ru.vsuet.schedule.importer.domain.ImportRun;
import ru.vsuet.schedule.importer.domain.ScheduleSource;
import ru.vsuet.schedule.importer.infrastructure.ImportRunRepository;
import ru.vsuet.schedule.importer.infrastructure.ScheduleSourceRepository;
import ru.vsuet.schedule.importer.parser.ParseDiagnostics;
import ru.vsuet.schedule.importer.parser.ParseIssue;
import ru.vsuet.schedule.importer.parser.ParsedSchedule;
import ru.vsuet.schedule.importer.parser.ParserRegistry;
import ru.vsuet.schedule.importer.parser.ScheduleParseException;

/**
 * Orchestrates one import: hash the file, skip it if unchanged, parse it, then hand the result to
 * {@link ScheduleImportApplier}. This class is deliberately NOT transactional: parsing needs no database,
 * the history row is saved in its own short transaction (so a failed import is still recorded), and only the
 * applier opens the one transaction that changes the schedule.
 *
 * <p>Downloading the file is a separate concern (the caller passes the bytes in).
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final ScheduleSourceRepository sources;
    private final ImportRunRepository runs;
    private final ParserRegistry parsers;
    private final ScheduleImportApplier applier;
    private final Clock clock;

    ImportService(ScheduleSourceRepository sources, ImportRunRepository runs, ParserRegistry parsers,
                  ScheduleImportApplier applier, Clock clock) {
        this.sources = sources;
        this.runs = runs;
        this.parsers = parsers;
        this.applier = applier;
        this.clock = clock;
    }

    /** Imports {@code content} (the bytes of the source's file). Never throws for a bad file: see the result. */
    public ImportResult importFile(long sourceId, byte[] content) {
        ScheduleSource source = sources.findById(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown schedule source " + sourceId));
        String sha256 = Hashes.sha256Hex(content);
        sources.touchChecked(sourceId, clock.instant());
        ImportRun run = runs.save(ImportRun.start(sourceId, sha256, clock.instant()));

        try {
            if (sha256.equals(source.getLastSha256())) {
                run.unchanged(clock.instant());
                runs.save(run);
                return ImportResult.unchanged(run.getId());
            }
            ParsedSchedule parsed = parsers.get(source.getParserKey()).parse(new ByteArrayInputStream(content));
            return applier.apply(run.getId(), sourceId, parsed, sha256);
        } catch (ScheduleParseException e) {
            return fail(run, e.getMessage(), e.diagnostics(), List.of(), e);
        } catch (ImportRejectedException e) {
            return fail(run, e.getMessage(), null, e.issues(), e);
        } catch (RuntimeException e) {
            return fail(run, "Unexpected error: " + e.getMessage(), null, List.of(), e);
        }
    }

    /** Records that the file could not even be downloaded; the current schedule is untouched. */
    public ImportResult recordDownloadFailure(long sourceId, FileDownloadException cause) {
        sources.touchChecked(sourceId, clock.instant());
        ImportRun run = runs.save(ImportRun.start(sourceId, null, clock.instant()));
        return fail(run, "Download failed: " + cause.getMessage(), null, List.of(), cause);
    }

    private ImportResult fail(ImportRun run, String message, ParseDiagnostics diagnostics, List<ParseIssue> issues,
                              Throwable cause) {
        log.warn("Import run {} failed: {}", run.getId(), message, cause);
        run.failed(message,
                diagnostics == null ? null : diagnostics.parserVersion(),
                diagnostics == null ? null : diagnostics.totalFound(),
                diagnostics == null ? null : diagnostics.recognized(),
                diagnostics == null ? null : diagnostics.skipped(),
                ImportDiagnosticsJson.write(diagnostics, issues), clock.instant());
        runs.save(run);
        return ImportResult.failed(run.getId(), message);
    }
}
