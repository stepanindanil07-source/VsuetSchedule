package ru.vsuet.schedule.academic.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import ru.vsuet.schedule.academic.domain.OrganizationUnit;
import ru.vsuet.schedule.academic.domain.StudyGroup;

/** JSON shapes of the catalog endpoints. They are a contract with the mobile client: change them compatibly. */
final class CatalogDtos {

    private CatalogDtos() {
    }

    @Schema(description = "A faculty that publishes a schedule")
    record FacultyDto(long id, String code, String name) {
        static FacultyDto of(OrganizationUnit unit) {
            return new FacultyDto(unit.getId(), unit.getCode(), unit.getName());
        }
    }

    @Schema(description = "A study group of a faculty")
    record GroupDto(
            long id,
            String code,
            @Schema(description = "Year of study (1-6) as the schedule file states it; null when the file does not say "
                    + "(e.g. a sheet that mixes masters and 5th-year groups)", nullable = true, minimum = "1", maximum = "6")
            Integer course,
            @Schema(description = "Increases whenever this group's lessons change; compare with the stored one to detect updates")
            long scheduleVersion,
            @Schema(description = "When the lessons last changed; null if the group has no schedule yet", nullable = true)
            Instant scheduleUpdatedAt) {
        static GroupDto of(StudyGroup group) {
            return new GroupDto(group.getId(), group.getCode(), group.getCourse(), group.getScheduleVersion(),
                    group.getScheduleUpdatedAt());
        }
    }
}
