package ru.vsuet.schedule.admin.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.vsuet.schedule.admin.api.AdminDtos.CreatePeriodRequest;
import ru.vsuet.schedule.admin.api.AdminDtos.PeriodDto;
import ru.vsuet.schedule.admin.api.AdminDtos.PeriodUpdatedDto;
import ru.vsuet.schedule.admin.api.AdminDtos.UpdatePeriodRequest;
import ru.vsuet.schedule.admin.application.PeriodAdminService;

@RestController
@RequestMapping("/api/v1/admin/periods")
@Tag(name = "Admin: semesters")
@SecurityRequirement(name = "adminToken")
class PeriodAdminController {

    private final PeriodAdminService periods;

    PeriodAdminController(PeriodAdminService periods) {
        this.periods = periods;
    }

    @GetMapping
    @Operation(summary = "List semesters, newest first")
    List<PeriodDto> list() {
        return periods.list().stream().map(PeriodDto::of).toList();
    }

    @PostMapping
    @Operation(summary = "Register a semester",
            description = "Needed before the files of a new semester can be imported. 400 for an impossible combination of dates, "
                    + "409 for a duplicate or an overlap with another semester.")
    ResponseEntity<PeriodDto> create(@Valid @RequestBody CreatePeriodRequest request) {
        PeriodDto created = PeriodDto.of(periods.create(request.term(), request.yearFrom(), request.dates()));
        return ResponseEntity.created(URI.create("/api/v1/admin/periods/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Change the dates or the week-parity anchor of a semester",
            description = "If anything changed, the schedule version of every group with lessons in the semester is raised so "
                    + "that apps reload the schedule (and with it the new anchor).")
    PeriodUpdatedDto update(@PathVariable long id, @Valid @RequestBody UpdatePeriodRequest request) {
        var updated = periods.update(id, request.dates());
        return new PeriodUpdatedDto(PeriodDto.of(updated.period()), updated.groupsRefreshed());
    }
}
