package ru.vsuet.schedule.academic.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.vsuet.schedule.academic.domain.AcademicPeriod;
import ru.vsuet.schedule.academic.infrastructure.AcademicPeriodRepository;

/** Answers "what is today and which semester is current" in the university's time zone. */
@Service
public class AcademicCalendar {

    private final AcademicPeriodRepository periods;
    private final Clock clock;
    private final ZoneId zone;

    AcademicCalendar(AcademicPeriodRepository periods, Clock clock,
                     @Value("${vsuet.timezone:Europe/Moscow}") ZoneId zone) {
        this.periods = periods;
        this.clock = clock;
        this.zone = zone;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }

    /** The semester that is running; between semesters the one that ended last; before the first, the first. */
    public Optional<AcademicPeriod> currentPeriod() {
        return periods.findFirstByStartDateLessThanEqualOrderByStartDateDesc(today())
                .or(periods::findFirstByOrderByStartDateAsc);
    }
}
