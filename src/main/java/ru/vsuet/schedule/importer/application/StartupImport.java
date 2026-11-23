package ru.vsuet.schedule.importer.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Imports every enabled source once, right after startup, when {@code vsuet.import.run-on-startup=true} (the production
 * profile): a fresh database would otherwise have no schedule until the next scheduled run. It runs on its own thread so
 * that a slow university site never delays the application becoming ready, and a failure is logged, never fatal.
 */
@Component
class StartupImport {

    private static final Logger log = LoggerFactory.getLogger(StartupImport.class);

    private final ImportProperties properties;
    private final ScheduledImportJob job;

    StartupImport(ImportProperties properties, ScheduledImportJob job) {
        this.properties = properties;
        this.job = job;
    }

    @EventListener(ApplicationReadyEvent.class)
    void importOnStartup() {
        if (!properties.runOnStartup()) {
            return;
        }
        Thread.ofVirtual().name("startup-import").start(() -> {
            try {
                log.info("Importing all enabled sources after startup");
                job.importAllEnabledSources();
            } catch (RuntimeException e) {
                log.error("The import after startup failed", e);
            }
        });
    }
}
