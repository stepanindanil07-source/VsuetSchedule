package ru.vsuet.schedule.schedule.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import ru.vsuet.schedule.academic.domain.AcademicPeriod;
import ru.vsuet.schedule.academic.domain.AcademicTerm;
import ru.vsuet.schedule.academic.domain.StudyGroup;
import ru.vsuet.schedule.common.WeekType;
import ru.vsuet.schedule.schedule.application.GroupSchedule;
import ru.vsuet.schedule.schedule.domain.LessonType;
import ru.vsuet.schedule.schedule.domain.ScheduleEntry;

/**
 * JSON shapes of the schedule endpoints: a contract with the mobile client. Adding fields is safe;
 * renaming, removing or changing the meaning of a field breaks installed apps.
 */
final class ScheduleDtos {

    private ScheduleDtos() {
    }

    @Schema(description = "Version of a group's schedule")
    record GroupVersionDto(long groupId,
                           @Schema(description = "Grows whenever the group's lessons change") long version,
                           @Schema(nullable = true) Instant updatedAt) {
        static GroupVersionDto of(StudyGroup group) {
            return new GroupVersionDto(group.getId(), group.getScheduleVersion(), group.getScheduleUpdatedAt());
        }
    }

    @Schema(description = "A semester and the week-parity anchor: week parity of any date is derived from "
            + "firstWeekStart/firstWeekType because weeks alternate")
    record PeriodDto(long id, AcademicTerm term, int yearFrom, int yearTo, LocalDate startDate, LocalDate endDate,
                     @Schema(description = "Monday of the first week") LocalDate firstWeekStart,
                     @Schema(description = "NUMERATOR or DENOMINATOR") WeekType firstWeekType) {
        static PeriodDto of(AcademicPeriod p) {
            return new PeriodDto(p.getId(), p.getTerm(), p.getYearFrom(), p.getYearTo(), p.getStartDate(), p.getEndDate(),
                    p.getFirstWeekStart(), p.getFirstWeekType());
        }
    }

    @Schema(description = "One lesson")
    record LessonDto(
            DayOfWeek dayOfWeek,
            @JsonFormat(pattern = "HH:mm") @Schema(type = "string", example = "08:00") LocalTime startTime,
            @JsonFormat(pattern = "HH:mm") @Schema(type = "string", example = "09:35") LocalTime endTime,
            @Schema(description = "NUMERATOR, DENOMINATOR or EVERY_WEEK") WeekType weekType,
            @Schema(description = "1-based subgroup number; null = the whole group", nullable = true) Integer subgroup,
            String subject,
            LessonType lessonType,
            @Schema(description = "Teacher(s) as written in the source, several separated by a comma", nullable = true)
            String teacher,
            @Schema(description = "Room(s) as written in the source, several separated by a comma", nullable = true)
            String room) {
        static LessonDto of(ScheduleEntry e) {
            return new LessonDto(e.getDayOfWeek(), e.getStartTime(), e.getEndTime(), e.getWeekType(), e.getSubgroup(),
                    e.getSubject(), e.getLessonType(), e.getTeacherText(), e.getRoomText());
        }
    }

    @Schema(description = "A group's schedule for the current semester, lessons ordered by day and time")
    record GroupScheduleDto(
            long groupId,
            String groupCode,
            long version,
            @Schema(nullable = true) Instant updatedAt,
            @Schema(description = "Current semester; null if none is registered", nullable = true) PeriodDto period,
            @Schema(description = "Today's date in the university time zone (Europe/Moscow)") LocalDate today,
            @Schema(description = "Parity of the current week; null without a period", nullable = true) WeekType currentWeekType,
            List<LessonDto> lessons) {
        static GroupScheduleDto of(GroupSchedule s) {
            return new GroupScheduleDto(s.group().getId(), s.group().getCode(), s.group().getScheduleVersion(),
                    s.group().getScheduleUpdatedAt(), s.period() == null ? null : PeriodDto.of(s.period()), s.today(),
                    s.currentWeekType(), s.entries().stream().map(LessonDto::of).toList());
        }
    }
}
