package ru.vsuet.schedule.academic.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A study group. Matched by (unit, code) and never deleted by an import, so ids stay stable for clients.
 * {@code scheduleVersion} grows only when this group's lessons really change.
 */
@Entity
@Table(name = "study_group")
public class StudyGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unit_id")
    private Long unitId;

    private String code;
    private boolean active;

    @Column(name = "schedule_version")
    private long scheduleVersion;

    @Column(name = "schedule_updated_at")
    private Instant scheduleUpdatedAt;

    @Column(name = "content_hash")
    private String contentHash;

    /** The import source that last saw this group in its file. */
    @Column(name = "source_id")
    private Long sourceId;

    protected StudyGroup() {
    }

    public StudyGroup(Long unitId, String code, Long sourceId) {
        this.unitId = unitId;
        this.code = code;
        this.sourceId = sourceId;
        this.active = true;
    }

    /** The group is present in the file of the given source: it is shown and now belongs to that source. */
    public void seenIn(Long sourceId) {
        this.sourceId = sourceId;
        this.active = true;
    }

    /** Records that the group's lessons changed: new content hash, next version. */
    public void contentChanged(String newHash, Instant now) {
        this.contentHash = newHash;
        this.scheduleVersion++;
        this.scheduleUpdatedAt = now;
    }

    public void deactivate() {
        this.active = false;
    }

    public Long getId() {
        return id;
    }

    public Long getUnitId() {
        return unitId;
    }

    public String getCode() {
        return code;
    }

    public boolean isActive() {
        return active;
    }

    public long getScheduleVersion() {
        return scheduleVersion;
    }

    public Instant getScheduleUpdatedAt() {
        return scheduleUpdatedAt;
    }

    public String getContentHash() {
        return contentHash;
    }

    public Long getSourceId() {
        return sourceId;
    }
}
