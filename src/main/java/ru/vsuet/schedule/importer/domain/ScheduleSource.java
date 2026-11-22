package ru.vsuet.schedule.importer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One published schedule file: which unit it belongs to, which parser reads it and where it is downloaded from. */
@Entity
@Table(name = "schedule_source")
public class ScheduleSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unit_id")
    private Long unitId;

    @Column(name = "parser_key")
    private String parserKey;

    private String url;
    private boolean enabled;

    /** SHA-256 of the last file that was imported successfully. */
    @Column(name = "last_sha256")
    private String lastSha256;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    /** When set, lessons are stored in this period whatever semester the file text claims. */
    @Column(name = "academic_period_id")
    private Long academicPeriodId;

    protected ScheduleSource() {
    }

    public ScheduleSource(Long unitId, String parserKey, String url) {
        this.unitId = unitId;
        this.parserKey = parserKey;
        this.url = url;
        this.enabled = true;
    }

    public void disable() {
        this.enabled = false;
    }

    public void pinToPeriod(Long periodId) {
        this.academicPeriodId = periodId;
    }

    public void markImported(String sha256, Instant now) {
        this.lastSha256 = sha256;
        this.lastCheckedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getUnitId() {
        return unitId;
    }

    public String getParserKey() {
        return parserKey;
    }

    public String getUrl() {
        return url;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getLastSha256() {
        return lastSha256;
    }

    public Instant getLastCheckedAt() {
        return lastCheckedAt;
    }

    public Long getAcademicPeriodId() {
        return academicPeriodId;
    }
}
