package ru.vsuet.schedule.schedule.application;

import java.time.LocalDate;
import java.util.List;
import ru.vsuet.schedule.academic.domain.AcademicPeriod;
import ru.vsuet.schedule.academic.domain.StudyGroup;
import ru.vsuet.schedule.common.WeekType;
import ru.vsuet.schedule.schedule.domain.ScheduleEntry;

/**
 * A group's schedule for the current semester.
 *
 * @param period          {@code null} when no semester is registered yet
 * @param currentWeekType parity of the week that contains {@code today}; {@code null} without a period
 */
public record GroupSchedule(StudyGroup group, AcademicPeriod period, LocalDate today, WeekType currentWeekType,
                            List<ScheduleEntry> entries) {
}
