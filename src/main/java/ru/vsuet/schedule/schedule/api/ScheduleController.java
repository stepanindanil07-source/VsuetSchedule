package ru.vsuet.schedule.schedule.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import ru.vsuet.schedule.academic.domain.StudyGroup;
import ru.vsuet.schedule.common.WeekType;
import ru.vsuet.schedule.schedule.api.ScheduleDtos.GroupScheduleDto;
import ru.vsuet.schedule.schedule.api.ScheduleDtos.GroupVersionDto;
import ru.vsuet.schedule.schedule.application.ScheduleQueryService;

@RestController
@RequestMapping("/api/v1/groups/{groupId}/schedule")
@Tag(name = "Schedule")
class ScheduleController {

    private final ScheduleQueryService service;

    ScheduleController(ScheduleQueryService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Schedule of a group for the current semester",
            description = "Lessons are ordered by day of week and start time. 404 if the group is unknown or no longer "
                    + "in the schedule file. A bad `weekType` or a `subgroup` below 1 gives 400.")
    GroupScheduleDto schedule(
            @PathVariable long groupId,
            @Parameter(description = "NUMERATOR or DENOMINATOR: keep that week's lessons plus every-week lessons. "
                    + "Omit (or EVERY_WEEK) to get everything.")
            @RequestParam(required = false) WeekType weekType,
            @Parameter(description = "Keep this subgroup's lessons plus lessons for the whole group. Omit to get everything.")
            @RequestParam(required = false) @Min(1) Integer subgroup) {
        return GroupScheduleDto.of(service.schedule(groupId, weekType, subgroup));
    }

    @GetMapping("/version")
    @Operation(summary = "Version of a group's schedule",
            description = "Cheap check before downloading the schedule. Sends an ETag; a request with a matching "
                    + "If-None-Match gets 304 Not Modified.")
    ResponseEntity<GroupVersionDto> version(@PathVariable long groupId, WebRequest request) {
        StudyGroup group = service.groupVersion(groupId);
        String etag = "\"" + group.getScheduleVersion() + "\"";
        if (request.checkNotModified(etag)) {
            return null; // 304 has already been written
        }
        return ResponseEntity.ok().eTag(etag).cacheControl(CacheControl.noCache()).body(GroupVersionDto.of(group));
    }
}
