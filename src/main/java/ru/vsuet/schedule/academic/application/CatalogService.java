package ru.vsuet.schedule.academic.application;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.vsuet.schedule.academic.domain.OrganizationUnit;
import ru.vsuet.schedule.academic.domain.StudyGroup;
import ru.vsuet.schedule.academic.infrastructure.OrganizationUnitRepository;
import ru.vsuet.schedule.academic.infrastructure.StudyGroupRepository;
import ru.vsuet.schedule.common.NotFoundException;

/** Read access to faculties and their groups. */
@Service
@Transactional(readOnly = true)
public class CatalogService {

    private final OrganizationUnitRepository units;
    private final StudyGroupRepository groups;

    CatalogService(OrganizationUnitRepository units, StudyGroupRepository groups) {
        this.units = units;
        this.groups = groups;
    }

    public List<OrganizationUnit> faculties() {
        return units.findAll(org.springframework.data.domain.Sort.by("name"));
    }

    /** Groups currently present in the schedule, sorted by code. */
    public List<StudyGroup> activeGroups(long facultyId) {
        if (!units.existsById(facultyId)) {
            throw new NotFoundException("Faculty " + facultyId + " not found");
        }
        return groups.findByUnitIdAndActiveTrueOrderByCode(facultyId);
    }
}
