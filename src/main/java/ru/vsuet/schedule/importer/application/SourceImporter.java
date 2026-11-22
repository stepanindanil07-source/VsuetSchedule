package ru.vsuet.schedule.importer.application;

import org.springframework.stereotype.Service;
import ru.vsuet.schedule.importer.domain.ScheduleSource;
import ru.vsuet.schedule.importer.infrastructure.ScheduleSourceRepository;

/**
 * Imports one configured source end to end: download, then {@link ImportService}. This is also the entry point
 * for a manual import. A failed download is recorded in the import history like any other failure.
 */
@Service
public class SourceImporter {

    private final ScheduleSourceRepository sources;
    private final ScheduleFileFetcher fetcher;
    private final ImportService importService;

    SourceImporter(ScheduleSourceRepository sources, ScheduleFileFetcher fetcher, ImportService importService) {
        this.sources = sources;
        this.fetcher = fetcher;
        this.importService = importService;
    }

    /** @throws IllegalArgumentException for an unknown source; @throws IllegalStateException for a disabled one */
    public ImportResult importSource(long sourceId) {
        ScheduleSource source = sources.findById(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown schedule source " + sourceId));
        if (!source.isEnabled()) {
            throw new IllegalStateException("Schedule source " + sourceId + " is disabled");
        }
        byte[] content;
        try {
            content = fetcher.fetch(source.getUrl());
        } catch (FileDownloadException e) {
            return importService.recordDownloadFailure(sourceId, e);
        }
        return importService.importFile(sourceId, content);
    }
}
