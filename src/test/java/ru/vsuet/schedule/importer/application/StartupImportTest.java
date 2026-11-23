package ru.vsuet.schedule.importer.application;

import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class StartupImportTest {

    private static ImportProperties properties(boolean runOnStartup) {
        return new ImportProperties(0.5, Duration.ofHours(6), runOnStartup);
    }

    @Test
    void importsEverySourceInTheBackgroundWhenSwitchedOn() {
        ScheduledImportJob job = mock(ScheduledImportJob.class);

        new StartupImport(properties(true), job).importOnStartup();

        verify(job, timeout(2000)).importAllEnabledSources();
    }

    @Test
    void doesNothingWhenSwitchedOff() {
        ScheduledImportJob job = mock(ScheduledImportJob.class);

        new StartupImport(properties(false), job).importOnStartup();

        verify(job, after(300).never()).importAllEnabledSources();
    }

    @Test
    void aFailingImportIsLoggedNotThrownIntoTheStartup() {
        ScheduledImportJob job = mock(ScheduledImportJob.class);
        when(job.importAllEnabledSources()).thenThrow(new IllegalStateException("site is down"));

        new StartupImport(properties(true), job).importOnStartup();   // must not throw

        verify(job, timeout(2000)).importAllEnabledSources();
    }
}
