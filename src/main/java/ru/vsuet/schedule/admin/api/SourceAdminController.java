package ru.vsuet.schedule.admin.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.vsuet.schedule.admin.api.AdminDtos.EnableSourceRequest;
import ru.vsuet.schedule.admin.api.AdminDtos.ImportResultDto;
import ru.vsuet.schedule.admin.api.AdminDtos.ImportRunDto;
import ru.vsuet.schedule.admin.api.AdminDtos.PinSourceRequest;
import ru.vsuet.schedule.admin.api.AdminDtos.SourceDto;
import ru.vsuet.schedule.admin.application.SourceAdminService;
import ru.vsuet.schedule.common.NotFoundException;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin: files and imports")
@SecurityRequirement(name = "adminToken")
class SourceAdminController {

    private final SourceAdminService sources;

    SourceAdminController(SourceAdminService sources) {
        this.sources = sources;
    }

    @GetMapping("/sources")
    @Operation(summary = "List the schedule files with their latest import")
    List<SourceDto> list() {
        return sources.list().stream().map(SourceDto::of).toList();
    }

    @PutMapping("/sources/{id}/period")
    @Operation(summary = "Pin a file to a semester (or remove the pin)",
            description = "A pinned file is stored in that semester whatever semester its text states. The next import re-reads "
                    + "the file even if it did not change.")
    SourceDto pin(@PathVariable long id, @RequestBody PinSourceRequest request) {
        return SourceDto.of(sources.pin(id, request.periodId()));
    }

    @PutMapping("/sources/{id}/enabled")
    @Operation(summary = "Switch a file's automatic import on or off")
    SourceDto enable(@PathVariable long id, @Valid @RequestBody EnableSourceRequest request) {
        return SourceDto.of(sources.setEnabled(id, request.enabled()));
    }

    @PostMapping("/sources/{id}/import")
    @Operation(summary = "Download and import one file now",
            description = "Blocks until the import is finished. A file that cannot be downloaded or read is a normal answer "
                    + "with status FAILED; the current schedule is never touched by a failed import. 409 if the file is disabled.")
    ImportResultDto importOne(@PathVariable long id) {
        return ImportResultDto.of(sources.importNow(id));
    }

    @PostMapping("/imports")
    @Operation(summary = "Download and import every enabled file now", description = "Blocks until all are done.")
    List<ImportResultDto> importAll() {
        return sources.importAllEnabled().stream().map(ImportResultDto::of).toList();
    }

    @GetMapping("/sources/{id}/runs")
    @Operation(summary = "Import history of a file, newest first")
    List<ImportRunDto> history(@PathVariable long id,
                               @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return sources.history(id, limit).stream().map(ImportRunDto::of).toList();
    }

    @GetMapping(value = "/import-runs/{runId}/diagnostics", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "What the parser found in one import: counters and the list of warnings and errors",
            description = "The stored JSON document as is.")
    String diagnostics(@PathVariable long runId) {
        String json = sources.run(runId).getDiagnostics();
        if (json == null) {
            throw new NotFoundException("Import run " + runId + " has no diagnostics");
        }
        return json;
    }
}
