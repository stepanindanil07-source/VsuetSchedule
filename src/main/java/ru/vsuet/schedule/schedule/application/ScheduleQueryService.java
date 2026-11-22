package ru.vsuet.schedule.schedule.application;

import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.vsuet.schedule.academic.application.AcademicCalendar;
import ru.vsuet.schedule.academic.domain.AcademicPeriod;
import ru.vsuet.schedule.academic.domain.StudyGroup;
import ru.vsuet.schedule.academic.infrastructure.StudyGroupRepository;
import ru.vsuet.schedule.common.NotFoundException;
import ru.vsuet.schedule.common.WeekType;
import ru.vsuet.schedule.schedule.domain.ScheduleEntry;
import ru.vsuet.schedule.schedule.infrastructure.ScheduleEntryRepository;

/** Read side of the schedule: what the mobile client asks for. */
@Service
@Transactional(readOnly = true)
public class ScheduleQueryService {

    /**
     * Day, start time, then numerator before denominator before every-week (the enum's own order: sorting by the
     * stored text would put DENOMINATOR first), then whole-group before subgroups. A group has dozens of lessons,
     * so sorting in memory is simpler than encoding this order in SQL.
     */
    private static final Comparator<ScheduleEntry> DISPLAY_ORDER = Comparator
            .comparing(ScheduleEntry::getDayOfWeek)
            .thenComparing(ScheduleEntry::getStartTime)
            .thenComparing(ScheduleEntry::getWeekType)
            .thenComparing(ScheduleEntry::getSubgroup, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(ScheduleEntry::getId);

    private final StudyGroupRepository groups;
    private final ScheduleEntryRepository entries;
    private final AcademicCalendar calendar;

    ScheduleQueryService(StudyGroupRepository groups, ScheduleEntryRepository entries, AcademicCalendar calendar) {
        this.groups = groups;
        this.entries = entries;
        this.calendar = calendar;
    }

    /** Only the version counters of the group: tiny, used by the client to decide whether to download anything. */
    public StudyGroup groupVersion(long groupId) {
        return activeGroup(groupId);
    }

    /**
     * @param week     {@code NUMERATOR} / {@code DENOMINATOR}: keep lessons of that week plus every-week lessons;
     *                 {@code null} or {@code EVERY_WEEK}: no week filtering
     * @param subgroup keep lessons of that subgroup plus lessons for the whole group; {@code null}: no filtering
     */
    public GroupSchedule schedule(long groupId, WeekType week, Integer subgroup) {
        StudyGroup group = activeGroup(groupId);
        AcademicPeriod period = calendar.currentPeriod().orElse(null);
        if (period == null) {
            return new GroupSchedule(group, null, calendar.today(), null, List.of());
        }
        List<ScheduleEntry> lessons = entries.findByGroupIdAndPeriodId(group.getId(), period.getId())
                .stream()
                .filter(e -> matchesWeek(e, week))
                .filter(e -> matchesSubgroup(e, subgroup))
                .sorted(DISPLAY_ORDER)
                .toList();
        return new GroupSchedule(group, period, calendar.today(), period.weekTypeOn(calendar.today()), lessons);
    }

    private StudyGroup activeGroup(long groupId) {
        return groups.findById(groupId)
                .filter(StudyGroup::isActive)
                .orElseThrow(() -> new NotFoundException("Group " + groupId + " not found"));
    }

    private static boolean matchesWeek(ScheduleEntry e, WeekType wanted) {
        return wanted == null || wanted == WeekType.EVERY_WEEK
                || e.getWeekType() == WeekType.EVERY_WEEK || e.getWeekType() == wanted;
    }

    private static boolean matchesSubgroup(ScheduleEntry e, Integer wanted) {
        return wanted == null || e.getSubgroup() == null || e.getSubgroup().equals(wanted);
    }
}
