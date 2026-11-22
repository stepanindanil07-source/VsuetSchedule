package ru.vsuet.schedule.importer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** History record of one import attempt. Written even when the attempt fails. */
@Entity
@Table(name = "import_run")
public class ImportRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    private ImportStatus status;

    private String sha256;

    @Column(name = "parser_version")
    private String parserVersion;

    @Column(name = "total_found")
    private Integer totalFound;

    private Integer recognized;
    private Integer skipped;

    /** JSON document: counters and the (capped) list of issues. */
    private String diagnostics;

    @Column(name = "period_id")
    private Long periodId;

    @Column(name = "error_message")
    private String errorMessage;

    protected ImportRun() {
    }

    public static ImportRun start(Long sourceId, String sha256, Instant now) {
        ImportRun run = new ImportRun();
        run.sourceId = sourceId;
        run.sha256 = sha256;
        run.startedAt = now;
        run.status = ImportStatus.RUNNING;
        return run;
    }

    public void unchanged(Instant now) {
        this.status = ImportStatus.SKIPPED_UNCHANGED;
        this.finishedAt = now;
    }

    @SuppressWarnings("java:S107")
    public void succeeded(Long periodId, String parserVersion, int totalFound, int recognized, int skipped,
                          String diagnosticsJson, Instant now) {
        this.status = ImportStatus.SUCCESS;
        this.finishedAt = now;
        this.periodId = periodId;
        this.parserVersion = parserVersion;
        this.totalFound = totalFound;
        this.recognized = recognized;
        this.skipped = skipped;
        this.diagnostics = diagnosticsJson;
    }

    public void failed(String errorMessage, String parserVersion, Integer totalFound, Integer recognized, Integer skipped,
                       String diagnosticsJson, Instant now) {
        this.status = ImportStatus.FAILED;
        this.finishedAt = now;
        this.errorMessage = errorMessage;
        this.parserVersion = parserVersion;
        this.totalFound = totalFound;
        this.recognized = recognized;
        this.skipped = skipped;
        this.diagnostics = diagnosticsJson;
    }

    public Long getId() {
        return id;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public ImportStatus getStatus() {
        return status;
    }

    public String getSha256() {
        return sha256;
    }

    public String getParserVersion() {
        return parserVersion;
    }

    public Integer getTotalFound() {
        return totalFound;
    }

    public Integer getRecognized() {
        return recognized;
    }

    public Integer getSkipped() {
        return skipped;
    }

    public String getDiagnostics() {
        return diagnostics;
    }

    public Long getPeriodId() {
        return periodId;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
