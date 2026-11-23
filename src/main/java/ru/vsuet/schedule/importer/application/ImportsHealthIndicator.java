package ru.vsuet.schedule.importer.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import ru.vsuet.schedule.academic.application.AcademicCalendar;
import ru.vsuet.schedule.academic.domain.AcademicPeriod;
import ru.vsuet.schedule.academic.infrastructure.AcademicPeriodRepository;
import ru.vsuet.schedule.importer.domain.ImportRun;
import ru.vsuet.schedule.importer.domain.ImportStatus;
import ru.vsuet.schedule.importer.domain.ScheduleSource;
import ru.vsuet.schedule.importer.infrastructure.ImportRunRepository;
import ru.vsuet.schedule.importer.infrastructure.ScheduleSourceRepository;

/**
 * Answers one question for an external monitor: "is the schedule still being kept up to date?". It is DOWN when an enabled
 * source has no successful import ({@code SUCCESS}, or {@code SKIPPED_UNCHANGED}: the file was read and had not changed)
 * newer than {@code vsuet.import.health-max-age}, or never had one. That covers an unreachable site, a file the parser
 * rejects, a changed layout and a stopped scheduler alike. The stored schedule stays available in all these cases, so this
 * is exposed as its own health group ({@code /actuator/health/imports}, which contains the contributor "importFreshness") and is NOT part of liveness or readiness:
 * restarting the application cannot fix a broken file. It is also DOWN when an enabled source is still pinned to a
 * semester that is over while a newer one has started: such a file keeps importing, but into the old semester (the trap of
 * the winter break, see docs/OPERATIONS.md). Which source is stale is in the details (not public) and in the
 * admin API.
 */
@Component("importFreshness")
class ImportsHealthIndicator implements HealthIndicator {

    private static final Set<ImportStatus> GOOD = Set.of(ImportStatus.SUCCESS, ImportStatus.SKIPPED_UNCHANGED);

    private final ScheduleSourceRepository sources;
    private final ImportRunRepository runs;
    private final AcademicPeriodRepository periods;
    private final AcademicCalendar calendar;
    private final ImportProperties properties;
    private final Clock clock;

    ImportsHealthIndicator(ScheduleSourceRepository sources, ImportRunRepository runs, AcademicPeriodRepository periods,
                           AcademicCalendar calendar, ImportProperties properties, Clock clock) {
        this.sources = sources;
        this.runs = runs;
        this.periods = periods;
        this.calendar = calendar;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Health health() {
        Instant now = clock.instant();
        Duration maxAge = properties.healthMaxAge();
        List<String> problems = new ArrayList<>();
        List<ScheduleSource> enabled = sources.findByEnabledTrueOrderById();
        AcademicPeriod current = calendar.currentPeriod().orElse(null);
        for (ScheduleSource source : enabled) {
            pinnedToAnOverSemester(source, current).ifPresent(problems::add);
            Instant lastGood = runs.findFirstBySourceIdAndStatusInOrderByIdDesc(source.getId(), GOOD)
                    .map(ImportRun::getFinishedAt).orElse(null);
            if (lastGood == null) {
                problems.add("source " + source.getId() + " (" + source.getUrl() + "): never imported successfully");
            } else if (Duration.between(lastGood, now).compareTo(maxAge) > 0) {
                problems.add("source " + source.getId() + " (" + source.getUrl() + "): last good import " + lastGood
                        + " is older than " + maxAge);
            }
        }
        Health.Builder health = problems.isEmpty() ? Health.up() : Health.down();
        return health.withDetail("enabledSources", enabled.size()).withDetail("problems", problems).build();
    }

    private java.util.Optional<String> pinnedToAnOverSemester(ScheduleSource source, AcademicPeriod current) {
        if (current == null || source.getAcademicPeriodId() == null) {
            return java.util.Optional.empty();
        }
        return periods.findById(source.getAcademicPeriodId())
                .filter(pinned -> pinned.getStartDate().isBefore(current.getStartDate()))
                .map(pinned -> "source " + source.getId() + " (" + source.getUrl() + "): pinned to the " + pinned.getTerm() + " "
                        + pinned.getYearFrom() + "-" + pinned.getYearTo() + " semester, but the " + current.getTerm() + " "
                        + current.getYearFrom() + "-" + current.getYearTo() + " semester has started: re-pin it");
    }
}
