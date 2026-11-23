package ru.vsuet.schedule.importer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import ru.vsuet.schedule.academic.application.AcademicCalendar;
import ru.vsuet.schedule.academic.domain.AcademicPeriod;
import ru.vsuet.schedule.academic.domain.AcademicTerm;
import ru.vsuet.schedule.academic.infrastructure.AcademicPeriodRepository;
import ru.vsuet.schedule.common.WeekType;
import ru.vsuet.schedule.importer.domain.ImportRun;
import ru.vsuet.schedule.importer.domain.ScheduleSource;
import ru.vsuet.schedule.importer.infrastructure.ImportRunRepository;
import ru.vsuet.schedule.importer.infrastructure.ScheduleSourceRepository;

/** "Is the schedule still being kept up to date?" for each way an import can go quiet. */
class ImportsHealthIndicatorTest {

    private static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");

    private final ScheduleSourceRepository sources = mock(ScheduleSourceRepository.class);
    private final ImportRunRepository runs = mock(ImportRunRepository.class);
    private final AcademicPeriodRepository periods = mock(AcademicPeriodRepository.class);
    private final AcademicCalendar calendar = mock(AcademicCalendar.class);
    private final ImportsHealthIndicator indicator = new ImportsHealthIndicator(sources, runs, periods, calendar,
            new ImportProperties(0.5, Duration.ofHours(6), false), Clock.fixed(NOW, ZoneOffset.UTC));

    private static ScheduleSource source(long id) {
        ScheduleSource s = mock(ScheduleSource.class);
        when(s.getId()).thenReturn(id);
        when(s.getUrl()).thenReturn("https://example.test/" + id + ".xlsx");
        return s;
    }

    private static ImportRun finishedAt(Instant when) {
        ImportRun run = ImportRun.start(1L, "sha", when);
        run.unchanged(when);
        return run;
    }

    private void lastGoodImport(long sourceId, Instant when) {
        when(runs.findFirstBySourceIdAndStatusInOrderByIdDesc(org.mockito.ArgumentMatchers.eq(sourceId), anyCollection()))
                .thenReturn(when == null ? Optional.empty() : Optional.of(finishedAt(when)));
    }

    @Test
    void upWhenEveryEnabledSourceWasImportedRecently() {
        ScheduleSource first = source(1);
        ScheduleSource second = source(2);
        when(sources.findByEnabledTrueOrderById()).thenReturn(List.of(first, second));
        lastGoodImport(1, NOW.minus(Duration.ofMinutes(5)));
        lastGoodImport(2, NOW.minus(Duration.ofHours(5)));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("enabledSources", 2);
    }

    @Test
    void downWhenASourceHasNoGoodImportWithinTheAllowedAge() {
        ScheduleSource first = source(1);
        ScheduleSource second = source(2);
        when(sources.findByEnabledTrueOrderById()).thenReturn(List.of(first, second));
        lastGoodImport(1, NOW.minus(Duration.ofMinutes(5)));
        lastGoodImport(2, NOW.minus(Duration.ofHours(7)));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("problems").toString()).contains("source 2").contains("older than PT6H")
                .doesNotContain("source 1");
    }

    @Test
    void downWhenASourceWasNeverImported() {
        ScheduleSource only = source(1);
        when(sources.findByEnabledTrueOrderById()).thenReturn(List.of(only));
        lastGoodImport(1, null);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("problems").toString()).contains("never imported successfully");
    }

    @Test
    void theAgeLimitIsInclusiveSoExactlyMaxAgeIsStillFine() {
        ScheduleSource only = source(1);
        when(sources.findByEnabledTrueOrderById()).thenReturn(List.of(only));
        lastGoodImport(1, NOW.minus(Duration.ofHours(6)));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void upWithNothingToWatchWhenNoSourceIsEnabled() {
        when(sources.findByEnabledTrueOrderById()).thenReturn(List.of());

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("enabledSources", 0);
    }

    @Test
    void aFailedLatestRunDoesNotMatterWhileAGoodOneIsRecentAndOnlyGoodRunsAreAsked() {
        // the repository is asked for SUCCESS / SKIPPED_UNCHANGED only, so a FAILED run in between is invisible here
        ScheduleSource only = source(1);
        when(sources.findByEnabledTrueOrderById()).thenReturn(List.of(only));
        lastGoodImport(1, NOW.minus(Duration.ofHours(2)));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        org.mockito.Mockito.verify(runs).findFirstBySourceIdAndStatusInOrderByIdDesc(anyLong(),
                org.mockito.ArgumentMatchers.argThat(statuses -> statuses.size() == 2
                        && statuses.contains(ru.vsuet.schedule.importer.domain.ImportStatus.SUCCESS)
                        && statuses.contains(ru.vsuet.schedule.importer.domain.ImportStatus.SKIPPED_UNCHANGED)));
    }

    // ---- a file pinned to a semester that is over -------------------------------------------------------

    private static AcademicPeriod period(AcademicTerm term, int yearFrom, String start, String firstMonday) {
        return new AcademicPeriod(yearFrom, term, java.time.LocalDate.parse(start), java.time.LocalDate.parse(start).plusMonths(5),
                java.time.LocalDate.parse(firstMonday), WeekType.NUMERATOR);
    }

    private ScheduleSource pinnedSource(long id, long periodId) {
        ScheduleSource s = source(id);
        when(s.getAcademicPeriodId()).thenReturn(periodId);
        return s;
    }

    @Test
    void downWhenAPinnedSourceIsLeftOnTheSemesterThatIsOver() {
        AcademicPeriod autumn = period(AcademicTerm.AUTUMN, 2026, "2026-09-01", "2026-08-31");
        AcademicPeriod spring = period(AcademicTerm.SPRING, 2026, "2027-02-08", "2027-02-08");
        ScheduleSource pinned = pinnedSource(1, 10);
        when(sources.findByEnabledTrueOrderById()).thenReturn(List.of(pinned));
        lastGoodImport(1, NOW.minus(Duration.ofMinutes(5)));
        when(calendar.currentPeriod()).thenReturn(Optional.of(spring));
        when(periods.findById(10L)).thenReturn(Optional.of(autumn));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("problems").toString()).contains("pinned to the AUTUMN 2026-2027").contains("re-pin it");
    }

    @Test
    void upWhenAPinnedSourceIsOnTheCurrentSemester() {
        AcademicPeriod spring = period(AcademicTerm.SPRING, 2026, "2027-02-08", "2027-02-08");
        ScheduleSource pinned = pinnedSource(1, 11);
        when(sources.findByEnabledTrueOrderById()).thenReturn(List.of(pinned));
        lastGoodImport(1, NOW.minus(Duration.ofMinutes(5)));
        when(calendar.currentPeriod()).thenReturn(Optional.of(spring));
        when(periods.findById(11L)).thenReturn(Optional.of(spring));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void upWhenAPinnedSourceIsAheadOfTheCurrentSemesterWhichIsHowStaffPrepareTheSwitch() {
        // the break between semesters: staff pin the files to spring before it starts; "current" is still autumn
        AcademicPeriod autumn = period(AcademicTerm.AUTUMN, 2026, "2026-09-01", "2026-08-31");
        AcademicPeriod spring = period(AcademicTerm.SPRING, 2026, "2027-02-08", "2027-02-08");
        ScheduleSource pinned = pinnedSource(1, 11);
        when(sources.findByEnabledTrueOrderById()).thenReturn(List.of(pinned));
        lastGoodImport(1, NOW.minus(Duration.ofMinutes(5)));
        when(calendar.currentPeriod()).thenReturn(Optional.of(autumn));
        when(periods.findById(11L)).thenReturn(Optional.of(spring));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }
}
