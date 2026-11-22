package ru.vsuet.schedule.admin.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import ru.vsuet.schedule.academic.domain.AcademicPeriod;
import ru.vsuet.schedule.academic.domain.AcademicTerm;
import ru.vsuet.schedule.admin.application.PeriodAdminService;
import ru.vsuet.schedule.admin.application.SourceAdminService.SourceView;
import ru.vsuet.schedule.common.WeekType;
import ru.vsuet.schedule.importer.application.ImportResult;
import ru.vsuet.schedule.importer.domain.ImportRun;
import ru.vsuet.schedule.importer.domain.ImportStatus;

/** JSON shapes of the administrative endpoints. */
final class AdminDtos {

    private AdminDtos() {
    }

    // ---- periods ------------------------------------------------------------------------------------------

    @Schema(description = "A semester and its week-parity anchor")
    record PeriodDto(long id, AcademicTerm term, int yearFrom, int yearTo, LocalDate startDate, LocalDate endDate,
                     @Schema(description = "Monday of the first week") LocalDate firstWeekStart,
                     @Schema(description = "Parity of that week: NUMERATOR or DENOMINATOR") WeekType firstWeekType) {
        static PeriodDto of(AcademicPeriod p) {
            return new PeriodDto(p.getId(), p.getTerm(), p.getYearFrom(), p.getYearTo(), p.getStartDate(), p.getEndDate(),
                    p.getFirstWeekStart(), p.getFirstWeekType());
        }
    }

    @Schema(description = "A new semester. The first week must be a Monday and contain startDate; for AUTUMN startDate is in "
            + "yearFrom, for SPRING in yearFrom + 1; dates must not overlap another semester.")
    record CreatePeriodRequest(
            @NotNull AcademicTerm term,
            @NotNull @Min(2000) @Max(2100) @Schema(description = "Calendar year in which the academic year starts") Integer yearFrom,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @NotNull @Schema(description = "A Monday", example = "2026-08-31") LocalDate firstWeekStart,
            @NotNull @Schema(description = "NUMERATOR or DENOMINATOR") WeekType firstWeekType) {
        PeriodAdminService.Dates dates() {
            return new PeriodAdminService.Dates(startDate, endDate, firstWeekStart, firstWeekType);
        }
    }

    @Schema(description = "New dates and anchor of an existing semester (term and year cannot change)")
    record UpdatePeriodRequest(
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @NotNull LocalDate firstWeekStart,
            @NotNull WeekType firstWeekType) {
        PeriodAdminService.Dates dates() {
            return new PeriodAdminService.Dates(startDate, endDate, firstWeekStart, firstWeekType);
        }
    }

    record PeriodUpdatedDto(PeriodDto period,
                            @Schema(description = "Groups whose schedule version was raised so that cached clients reload "
                                    + "the changed anchor") int groupsRefreshed) {
    }

    // ---- sources ------------------------------------------------------------------------------------------

    record LastImportDto(long runId, ImportStatus status, Instant startedAt, @Schema(nullable = true) Instant finishedAt,
                         @Schema(nullable = true) Integer recognized, @Schema(nullable = true) Integer skipped,
                         @Schema(nullable = true) String errorMessage) {
        static LastImportDto of(ImportRun r) {
            return new LastImportDto(r.getId(), r.getStatus(), r.getStartedAt(), r.getFinishedAt(), r.getRecognized(),
                    r.getSkipped(), r.getErrorMessage());
        }
    }

    @Schema(description = "A published schedule file")
    record SourceDto(long id, String unitCode, String unitName, String parserKey, String url, boolean enabled,
                     @Schema(description = "Period the file is stored in whatever semester it states; null = the file's own",
                             nullable = true) Long pinnedPeriodId,
                     @Schema(description = "Course of the groups when the file does not state it", nullable = true)
                     Integer defaultCourse,
                     @Schema(nullable = true) Instant lastCheckedAt,
                     @Schema(nullable = true) LastImportDto lastImport) {
        static SourceDto of(SourceView v) {
            var s = v.source();
            return new SourceDto(s.getId(), v.unit().getCode(), v.unit().getName(), s.getParserKey(), s.getUrl(),
                    s.isEnabled(), s.getAcademicPeriodId(), s.getDefaultCourse(), s.getLastCheckedAt(),
                    v.lastRun().map(LastImportDto::of).orElse(null));
        }
    }

    record PinSourceRequest(@Schema(description = "Period to pin to; null removes the pin", nullable = true) Long periodId) {
    }

    record EnableSourceRequest(@NotNull Boolean enabled) {
    }

    @Schema(description = "Outcome of one import; a failed import is a normal answer (status FAILED), the schedule is untouched")
    record ImportResultDto(ImportStatus status, long runId, String message, int groupsCreated, int groupsUpdated,
                           int groupsUnchanged, int groupsDeactivated, int entriesWritten) {
        static ImportResultDto of(ImportResult r) {
            return new ImportResultDto(r.status(), r.runId(), r.message(), r.groupsCreated(), r.groupsUpdated(),
                    r.groupsUnchanged(), r.groupsDeactivated(), r.entriesWritten());
        }
    }

    record ImportRunDto(long id, ImportStatus status, Instant startedAt, @Schema(nullable = true) Instant finishedAt,
                        @Schema(nullable = true) Long periodId, @Schema(nullable = true) String parserVersion,
                        @Schema(nullable = true) Integer recognized, @Schema(nullable = true) Integer skipped,
                        @Schema(nullable = true) String errorMessage) {
        static ImportRunDto of(ImportRun r) {
            return new ImportRunDto(r.getId(), r.getStatus(), r.getStartedAt(), r.getFinishedAt(), r.getPeriodId(),
                    r.getParserVersion(), r.getRecognized(), r.getSkipped(), r.getErrorMessage());
        }
    }
}
