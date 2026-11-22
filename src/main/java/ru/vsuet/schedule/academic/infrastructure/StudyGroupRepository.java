package ru.vsuet.schedule.academic.infrastructure;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.vsuet.schedule.academic.domain.StudyGroup;

public interface StudyGroupRepository extends JpaRepository<StudyGroup, Long> {

    List<StudyGroup> findByUnitId(Long unitId);

    List<StudyGroup> findByUnitIdAndActiveTrueOrderByCode(Long unitId);
}
