package ru.vsuet.schedule.importer.application;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.vsuet.schedule.importer.domain.ScheduleSource;
import ru.vsuet.schedule.importer.infrastructure.ScheduleSourceRepository;

/**
 * Periodically imports every enabled source. One broken source (site down, bad file) never stops the others.
 * The cron expression is {@code vsuet.import.cron} (default: every hour, at minute 0).
 */
@Component
public class ScheduledImportJob {

    private static final Logger log = LoggerFactory.getLogger(ScheduledImportJob.class);

    private final ScheduleSourceRepository sources;
    private final SourceImporter importer;

    ScheduledImportJob(ScheduleSourceRepository sources, SourceImporter importer) {
        this.sources = sources;
        this.importer = importer;
    }

    @Scheduled(cron = "${vsuet.import.cron:0 0 * * * *}")
    public void runScheduledImport() {
        importAllEnabledSources();
    }

    /** Separate from the scheduled entry point so it can be called (and tested) directly. */
    public List<ImportResult> importAllEnabledSources() {
        List<ScheduleSource> enabled = sources.findByEnabledTrueOrderById();
        log.info("Scheduled import of {} source(s)", enabled.size());
        return enabled.stream().map(this::importOne).filter(Objects::nonNull).toList();
    }

    private ImportResult importOne(ScheduleSource source) {
        try {
            ImportResult result = importer.importSource(source.getId());
            log.info("Source {} ({}): {} - {}", source.getId(), source.getUrl(), result.status(), result.message());
            return result;
        } catch (RuntimeException e) {
            log.error("Source {} ({}) could not be imported", source.getId(), source.getUrl(), e);
            return null;
        }
    }
}
