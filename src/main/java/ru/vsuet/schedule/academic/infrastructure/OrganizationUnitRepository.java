package ru.vsuet.schedule.academic.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.vsuet.schedule.academic.domain.OrganizationUnit;

public interface OrganizationUnitRepository extends JpaRepository<OrganizationUnit, Long> {
}
