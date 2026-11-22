package ru.vsuet.schedule.importer.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.vsuet.schedule.academic.domain.AcademicPeriod;
import ru.vsuet.schedule.academic.domain.StudyGroup;
import ru.vsuet.schedule.academic.infrastructure.AcademicPeriodRepository;
import ru.vsuet.schedule.academic.infrastructure.StudyGroupRepository;
import ru.vsuet.schedule.importer.domain.ImportRun;
import ru.vsuet.schedule.importer.domain.ImportStatus;
import ru.vsuet.schedule.importer.domain.ScheduleSource;
import ru.vsuet.schedule.importer.infrastructure.ImportRunRepository;
import ru.vsuet.schedule.importer.infrastructure.ScheduleSourceRepository;
import ru.vsuet.schedule.importer.parser.IssueSeverity;
import ru.vsuet.schedule.importer.parser.ParseIssue;
import ru.vsuet.schedule.importer.parser.ParsedEntry;
import ru.vsuet.schedule.importer.parser.ParsedGroup;
import ru.vsuet.schedule.importer.parser.ParsedSchedule;
import ru.vsuet.schedule.schedule.domain.ScheduleEntry;
import ru.vsuet.schedule.schedule.infrastructure.ScheduleEntryRepository;

/**
 * Writes an already parsed and validated schedule to the database in ONE transaction.
 *
 * <p>Nothing is deleted before this point: the file has been downloaded, parsed and checked by then. Inside the
 * transaction, only groups whose content really changed are rewritten. If anything throws, the whole
 * transaction rolls back and the previous schedule stays exactly as it was.
 */
@Service
public class ScheduleImportApplier {

    private final ScheduleSourceRepository sources;
    private final ImportRunRepository runs;
    private final AcademicPeriodRepository periods;
    private final StudyGroupRepository groups;
    private final ScheduleEntryRepository entries;
    private final ImportProperties properties;
    private final Clock clock;

    ScheduleImportApplier(ScheduleSourceRepository sources, ImportRunRepository runs, AcademicPeriodRepository periods,
                          StudyGroupRepository groups, ScheduleEntryRepository entries, ImportProperties properties,
                          Clock clock) {
        this.sources = sources;
        this.runs = runs;
        this.periods = periods;
        this.groups = groups;
        this.entries = entries;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public ImportResult apply(long runId, long sourceId, ParsedSchedule parsed, String sha256) {
        Instant now = clock.instant();
        ScheduleSource source = sources.findForUpdateById(sourceId)
                .orElseThrow(() -> new IllegalStateException("Schedule source " + sourceId + " disappeared"));
        ImportRun run = runs.findById(runId).orElseThrow();

        // Another import of the same file may have committed while this one was parsing.
        if (sha256.equals(source.getLastSha256())) {
            run.unchanged(now);
            return ImportResult.unchanged(runId);
        }

        List<ParseIssue> importIssues = new ArrayList<>();
        AcademicPeriod period = resolvePeriod(source, parsed, importIssues);
        rejectIfSuspiciousDrop(source, period, parsed, importIssues);

        Map<String, StudyGroup> existing = groups.findByUnitId(source.getUnitId()).stream()
                .collect(Collectors.toMap(StudyGroup::getCode, g -> g, (a, b) -> a, LinkedHashMap::new));
        Map<String, List<ParsedEntry>> entriesByGroup = parsed.entries().stream()
                .collect(Collectors.groupingBy(ParsedEntry::groupCode));

        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int written = 0;
        Set<String> seen = new HashSet<>();
        for (ParsedGroup parsedGroup : parsed.groups()) {
            seen.add(parsedGroup.code());
            boolean isNew = !existing.containsKey(parsedGroup.code());
            StudyGroup group = isNew
                    ? groups.save(new StudyGroup(source.getUnitId(), parsedGroup.code(), source.getId()))
                    : existing.get(parsedGroup.code());
            // a course stated in the file wins; otherwise the source knows it (zao/5_kurs.xlsx is the 5th course)
            group.seenIn(source.getId(), parsedGroup.course() != null ? parsedGroup.course() : source.getDefaultCourse());

            List<ParsedEntry> groupEntries = entriesByGroup.getOrDefault(parsedGroup.code(), List.of());
            String hash = GroupContentHasher.hash(period.getId(), groupEntries);
            if (hash.equals(group.getContentHash())) {
                unchanged++;
                continue;
            }
            entries.deleteByGroupAndPeriod(group.getId(), period.getId());
            entries.saveAll(groupEntries.stream().map(e -> toEntity(e, group, period, runId)).toList());
            group.contentChanged(hash, now);
            written += groupEntries.size();
            if (isNew) {
                created++;
            } else {
                updated++;
            }
        }

        // A group that vanished from THIS source's file is hidden, never deleted: its lessons and id stay.
        // Groups owned by another source of the same unit are not ours to hide.
        int deactivated = 0;
        for (StudyGroup group : existing.values()) {
            if (source.getId().equals(group.getSourceId()) && !seen.contains(group.getCode()) && group.isActive()) {
                group.deactivate();
                deactivated++;
            }
        }

        source.markImported(sha256, now);
        var diagnostics = parsed.diagnostics();
        run.succeeded(period.getId(), diagnostics.parserVersion(), diagnostics.totalFound(), diagnostics.recognized(),
                diagnostics.skipped(), ImportDiagnosticsJson.write(diagnostics, importIssues), now);
        return new ImportResult(ImportStatus.SUCCESS, runId, "Imported", created, updated, unchanged, deactivated, written);
    }

    /**
     * The source's pinned period wins over the semester text in the file (a mismatch is only a warning). A file that
     * does not state its semester at all can only be imported by a pinned source.
     */
    private AcademicPeriod resolvePeriod(ScheduleSource source, ParsedSchedule parsed, List<ParseIssue> issues) {
        var filePeriod = parsed.period();
        if (source.getAcademicPeriodId() != null) {
            AcademicPeriod pinned = periods.findById(source.getAcademicPeriodId())
                    .orElseThrow(() -> new IllegalStateException("Pinned period " + source.getAcademicPeriodId() + " not found"));
            if (filePeriod != null && (pinned.getYearFrom() != filePeriod.yearFrom() || pinned.getTerm() != filePeriod.term())) {
                issues.add(new ParseIssue(IssueSeverity.WARNING, "PERIOD_PINNED_MISMATCH",
                        "File says " + filePeriod.term() + " " + filePeriod.yearFrom() + "-" + filePeriod.yearTo()
                                + " but the source is pinned to " + pinned.getTerm() + " " + pinned.getYearFrom() + "-"
                                + pinned.getYearTo() + "; the pinned period is used", null, null));
            }
            return pinned;
        }
        if (filePeriod == null) {
            throw new ImportRejectedException("The file does not state its semester and the source is not pinned to an "
                    + "academic period; pin the source (schedule_source.academic_period_id) before importing",
                    List.of(new ParseIssue(IssueSeverity.ERROR, "PERIOD_UNKNOWN",
                            "No semester in the file and no pinned period", null, null)));
        }
        return periods.findByYearFromAndTerm((short) filePeriod.yearFrom(), filePeriod.term())
                .orElseThrow(() -> new ImportRejectedException(
                        "Academic period " + filePeriod.term() + " " + filePeriod.yearFrom() + "-" + filePeriod.yearTo()
                                + " is not registered; register it (with its first week) before importing",
                        List.of(new ParseIssue(IssueSeverity.ERROR, "PERIOD_NOT_REGISTERED",
                                "Period from the file is unknown", null, null))));
    }

    private void rejectIfSuspiciousDrop(ScheduleSource source, AcademicPeriod period, ParsedSchedule parsed,
                                        List<ParseIssue> issues) {
        runs.findFirstBySourceIdAndPeriodIdAndStatusOrderByIdDesc(source.getId(), period.getId(), ImportStatus.SUCCESS)
                .map(ImportRun::getRecognized)
                .filter(previous -> previous != null && previous > 0)
                .ifPresent(previous -> {
                    int now = parsed.diagnostics().recognized();
                    if (now < previous * properties.minEntriesRatio()) {
                        issues.add(new ParseIssue(IssueSeverity.ERROR, "SUSPICIOUS_DROP",
                                "Lessons dropped from " + previous + " to " + now, null, null));
                        throw new ImportRejectedException("Rejected: the file has " + now + " lessons while the previous import had "
                                + previous + " (less than " + Math.round(properties.minEntriesRatio() * 100) + "%)", issues);
                    }
                });
    }

    private static ScheduleEntry toEntity(ParsedEntry e, StudyGroup group, AcademicPeriod period, long runId) {
        return new ScheduleEntry(group.getId(), period.getId(), runId, e.dayOfWeek(), e.startTime(), e.endTime(),
                e.weekType(), e.subgroup(), e.subject(), e.lessonType(), e.teacherText(), e.roomText(), e.rawText(),
                e.location().container(), e.location().position(), e.date());
    }
}
