package ru.vsuet.schedule.schedule.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.time.LocalTime;
import ru.vsuet.schedule.common.WeekType;

/**
 * One lesson of a group. Foreign keys are plain ids on purpose: modules stay decoupled and
 * reading a schedule never triggers lazy loading.
 */
@Entity
@Table(name = "schedule_entry")
public class ScheduleEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id")
    private Long groupId;

    @Column(name = "period_id")
    private Long periodId;

    @Column(name = "import_run_id")
    private Long importRunId;

    @Convert(converter = DayOfWeekConverter.class)
    @Column(name = "day_of_week")
    private DayOfWeek dayOfWeek;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "week_type")
    private WeekType weekType;

    /** 1-based subgroup number; {@code null} = the whole group. */
    private Short subgroup;

    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(name = "lesson_type")
    private LessonType lessonType;

    @Column(name = "teacher_text")
    private String teacherText;

    @Column(name = "room_text")
    private String roomText;

    @Column(name = "raw_text")
    private String rawText;

    @Column(name = "source_sheet")
    private String sourceSheet;

    @Column(name = "source_range")
    private String sourceRange;

    protected ScheduleEntry() {
    }

    @SuppressWarnings("java:S107") // a flat record of one table row; a builder would only restate the fields
    public ScheduleEntry(Long groupId, Long periodId, Long importRunId, DayOfWeek dayOfWeek, LocalTime startTime,
                         LocalTime endTime, WeekType weekType, Integer subgroup, String subject, LessonType lessonType,
                         String teacherText, String roomText, String rawText, String sourceSheet, String sourceRange) {
        this.groupId = groupId;
        this.periodId = periodId;
        this.importRunId = importRunId;
        this.dayOfWeek = dayOfWeek;
        this.startTime = startTime;
        this.endTime = endTime;
        this.weekType = weekType;
        this.subgroup = subgroup == null ? null : subgroup.shortValue();
        this.subject = subject;
        this.lessonType = lessonType;
        this.teacherText = teacherText;
        this.roomText = roomText;
        this.rawText = rawText;
        this.sourceSheet = sourceSheet;
        this.sourceRange = sourceRange;
    }

    public Long getId() {
        return id;
    }

    public Long getGroupId() {
        return groupId;
    }

    public Long getPeriodId() {
        return periodId;
    }

    public Long getImportRunId() {
        return importRunId;
    }

    public DayOfWeek getDayOfWeek() {
        return dayOfWeek;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public WeekType getWeekType() {
        return weekType;
    }

    public Integer getSubgroup() {
        return subgroup == null ? null : subgroup.intValue();
    }

    public String getSubject() {
        return subject;
    }

    public LessonType getLessonType() {
        return lessonType;
    }

    public String getTeacherText() {
        return teacherText;
    }

    public String getRoomText() {
        return roomText;
    }

    public String getRawText() {
        return rawText;
    }

    public String getSourceSheet() {
        return sourceSheet;
    }

    public String getSourceRange() {
        return sourceRange;
    }
}
