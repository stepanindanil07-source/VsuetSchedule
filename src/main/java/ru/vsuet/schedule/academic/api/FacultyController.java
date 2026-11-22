package ru.vsuet.schedule.academic.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.vsuet.schedule.academic.api.CatalogDtos.FacultyDto;
import ru.vsuet.schedule.academic.api.CatalogDtos.GroupDto;
import ru.vsuet.schedule.academic.application.CatalogService;

@RestController
@RequestMapping("/api/v1/faculties")
@Tag(name = "Catalog")
class FacultyController {

    private final CatalogService catalog;

    FacultyController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    @Operation(summary = "List faculties", description = "Sorted by name.")
    List<FacultyDto> faculties() {
        return catalog.faculties().stream().map(FacultyDto::of).toList();
    }

    @GetMapping("/{facultyId}/groups")
    @Operation(summary = "List groups of a faculty",
            description = "Only groups that are present in the current schedule file, sorted by code. 404 for an unknown faculty.")
    List<GroupDto> groups(@PathVariable long facultyId) {
        return catalog.activeGroups(facultyId).stream().map(GroupDto::of).toList();
    }
}
