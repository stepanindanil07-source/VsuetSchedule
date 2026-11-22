package ru.vsuet.schedule.admin.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.vsuet.schedule.academic.domain.OrganizationUnit;
import ru.vsuet.schedule.academic.infrastructure.AcademicPeriodRepository;
import ru.vsuet.schedule.academic.infrastructure.OrganizationUnitRepository;
import ru.vsuet.schedule.common.ConflictException;
import ru.vsuet.schedule.common.InvalidRequestException;
import ru.vsuet.schedule.common.NotFoundException;
import ru.vsuet.schedule.importer.application.ImportResult;
import ru.vsuet.schedule.importer.application.ScheduledImportJob;
import ru.vsuet.schedule.importer.application.SourceImporter;
import ru.vsuet.schedule.importer.domain.ImportRun;
import ru.vsuet.schedule.importer.domain.ScheduleSource;
import ru.vsuet.schedule.importer.infrastructure.ImportRunRepository;
import ru.vsuet.schedule.importer.infrastructure.ScheduleSourceRepository;

/** Operating the schedule files: which semester each one belongs to, switching one off, importing on demand. */
@Service
public class SourceAdminService {

    /** A source together with the unit it belongs to and its latest import attempt, if any. */
    public record SourceView(ScheduleSource source, OrganizationUnit unit, Optional<ImportRun> lastRun) {
    }

    private final ScheduleSourceRepository sources;
    private final OrganizationUnitRepository units;
    private final AcademicPeriodRepository periods;
    private final ImportRunRepository runs;
    private final SourceImporter importer;
    private final ScheduledImportJob importJob;

    SourceAdminService(ScheduleSourceRepository sources, OrganizationUnitRepository units, AcademicPeriodRepository periods,
                       ImportRunRepository runs, SourceImporter importer, ScheduledImportJob importJob) {
        this.sources = sources;
        this.units = units;
        this.periods = periods;
        this.runs = runs;
        this.importer = importer;
        this.importJob = importJob;
    }

    @Transactional(readOnly = true)
    public List<SourceView> list() {
        Map<Long, OrganizationUnit> unitsById = units.findAll().stream()
                .collect(Collectors.toMap(OrganizationUnit::getId, Function.identity()));
        return sources.findAll(Sort.by("id")).stream()
                .map(s -> new SourceView(s, unitsById.get(s.getUnitId()), runs.findFirstBySourceIdOrderByIdDesc(s.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public SourceView get(long sourceId) {
        ScheduleSource source = find(sourceId);
        return new SourceView(source, units.findById(source.getUnitId()).orElseThrow(),
                runs.findFirstBySourceIdOrderByIdDesc(sourceId));
    }

    /**
     * Pins the source to a period, or unpins it ({@code null}: the semester named in the file is used). The next import
     * re-reads the file even if it did not change, because it now belongs to another period.
     */
    @Transactional
    public SourceView pin(long sourceId, Long periodId) {
        ScheduleSource source = find(sourceId);
        if (periodId != null && !periods.existsById(periodId)) {
            throw new InvalidRequestException("Period " + periodId + " does not exist");
        }
        source.changePinnedPeriod(periodId);
        return get(sourceId);
    }

    @Transactional
    public SourceView setEnabled(long sourceId, boolean enabled) {
        ScheduleSource source = find(sourceId);
        if (enabled) {
            source.enable();
        } else {
            source.disable();
        }
        return get(sourceId);
    }

    /** Downloads and imports now; blocks until done (a slow site can take a minute). Failures are in the result. */
    public ImportResult importNow(long sourceId) {
        ScheduleSource source = find(sourceId);
        if (!source.isEnabled()) {
            throw new ConflictException("Source " + sourceId + " is disabled; enable it first");
        }
        return importer.importSource(sourceId);
    }

    public List<ImportResult> importAllEnabled() {
        return importJob.importAllEnabledSources();
    }

    @Transactional(readOnly = true)
    public List<ImportRun> history(long sourceId, int limit) {
        find(sourceId);
        return runs.findBySourceIdOrderByIdDesc(sourceId, Limit.of(limit));
    }

    @Transactional(readOnly = true)
    public ImportRun run(long runId) {
        return runs.findById(runId).orElseThrow(() -> new NotFoundException("Import run " + runId + " not found"));
    }

    private ScheduleSource find(long sourceId) {
        return sources.findById(sourceId).orElseThrow(() -> new NotFoundException("Source " + sourceId + " not found"));
    }
}
