package ru.vsuet.schedule.admin.application;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.vsuet.schedule.academic.domain.AcademicPeriod;
import ru.vsuet.schedule.academic.domain.AcademicTerm;
import ru.vsuet.schedule.academic.infrastructure.AcademicPeriodRepository;
import ru.vsuet.schedule.common.ConflictException;
import ru.vsuet.schedule.common.InvalidRequestException;
import ru.vsuet.schedule.common.NotFoundException;
import ru.vsuet.schedule.common.WeekType;

/**
 * Semesters and their week-parity anchor, maintained by the university staff. A new semester has to be registered
 * here before its files can be imported. Every rule below catches a typo that would silently shift the parity of
 * every lesson of the semester.
 */
@Service
public class PeriodAdminService {

    private static final Logger log = LoggerFactory.getLogger(PeriodAdminService.class);

    /** The part of a semester that staff can change later; term and year are its identity. */
    public record Dates(LocalDate startDate, LocalDate endDate, LocalDate firstWeekStart, WeekType firstWeekType) {
    }

    /** @param groupsRefreshed groups whose schedule version was raised so that cached clients load the new anchor */
    public record Updated(AcademicPeriod period, int groupsRefreshed) {
    }

    private final AcademicPeriodRepository periods;
    private final JdbcClient jdbc;
    private final Clock clock;

    PeriodAdminService(AcademicPeriodRepository periods, JdbcClient jdbc, Clock clock) {
        this.periods = periods;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AcademicPeriod> list() {
        return periods.findAll(Sort.by(Sort.Direction.DESC, "startDate"));
    }

    @Transactional
    public AcademicPeriod create(AcademicTerm term, int yearFrom, Dates dates) {
        validate(term, yearFrom, dates);
        periods.findByYearFromAndTerm((short) yearFrom, term).ifPresent(existing -> {
            throw new ConflictException("The " + term + " semester " + yearFrom + "-" + (yearFrom + 1)
                    + " already exists (id " + existing.getId() + "); change it instead");
        });
        rejectOverlap(dates, null);
        return periods.save(new AcademicPeriod(yearFrom, term, dates.startDate(), dates.endDate(), dates.firstWeekStart(),
                dates.firstWeekType()));
    }

    /**
     * Changes dates and anchor. Clients keep the anchor inside the cached schedule and only reload it when the group's
     * schedule version changes, so the version of every group that has lessons in this semester is raised when
     * anything changed.
     */
    @Transactional
    public Updated update(long id, Dates dates) {
        AcademicPeriod period = periods.findById(id).orElseThrow(() -> new NotFoundException("Period " + id + " not found"));
        validate(period.getTerm(), period.getYearFrom(), dates);
        rejectOverlap(dates, id);
        boolean changed = !dates.startDate().equals(period.getStartDate()) || !dates.endDate().equals(period.getEndDate())
                || !dates.firstWeekStart().equals(period.getFirstWeekStart()) || dates.firstWeekType() != period.getFirstWeekType();
        period.reschedule(dates.startDate(), dates.endDate(), dates.firstWeekStart(), dates.firstWeekType());
        int refreshed = changed ? refreshGroupsOf(period.getId()) : 0;
        if (changed) {
            log.info("Period {} changed; schedule version raised for {} group(s)", id, refreshed);
        }
        return new Updated(period, refreshed);
    }

    private int refreshGroupsOf(long periodId) {
        return jdbc.sql("""
                UPDATE study_group SET schedule_version = schedule_version + 1, schedule_updated_at = :now
                WHERE id IN (SELECT DISTINCT group_id FROM schedule_entry WHERE period_id = :period)""")
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .param("period", periodId)
                .update();
    }

    private static void validate(AcademicTerm term, int yearFrom, Dates d) {
        if (d.firstWeekType() == WeekType.EVERY_WEEK) {
            throw new InvalidRequestException("firstWeekType must be NUMERATOR or DENOMINATOR");
        }
        if (d.firstWeekStart().getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new InvalidRequestException("firstWeekStart must be a Monday, " + d.firstWeekStart() + " is a "
                    + d.firstWeekStart().getDayOfWeek());
        }
        if (!d.startDate().isBefore(d.endDate())) {
            throw new InvalidRequestException("startDate must be before endDate");
        }
        if (d.startDate().isBefore(d.firstWeekStart()) || d.startDate().isAfter(d.firstWeekStart().plusDays(6))) {
            throw new InvalidRequestException("The first week (" + d.firstWeekStart() + " - " + d.firstWeekStart().plusDays(6)
                    + ") must contain the first day of the semester (" + d.startDate() + ")");
        }
        int expectedYear = term == AcademicTerm.AUTUMN ? yearFrom : yearFrom + 1;
        if (d.startDate().getYear() != expectedYear) {
            throw new InvalidRequestException("The " + term + " semester of " + yearFrom + "-" + (yearFrom + 1)
                    + " must start in " + expectedYear + ", not " + d.startDate().getYear());
        }
    }

    private void rejectOverlap(Dates d, Long ownId) {
        periods.findOverlapping(d.startDate(), d.endDate()).stream()
                .filter(p -> !p.getId().equals(ownId))
                .findFirst()
                .ifPresent(p -> {
                    throw new ConflictException("The dates overlap the " + p.getTerm() + " semester " + p.getYearFrom() + "-"
                            + p.getYearTo() + " (id " + p.getId() + ", " + p.getStartDate() + " - " + p.getEndDate() + ")");
                });
    }
}
