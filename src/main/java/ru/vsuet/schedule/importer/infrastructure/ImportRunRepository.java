package ru.vsuet.schedule.importer.infrastructure;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.vsuet.schedule.importer.domain.ImportRun;
import ru.vsuet.schedule.importer.domain.ImportStatus;

public interface ImportRunRepository extends JpaRepository<ImportRun, Long> {

    Optional<ImportRun> findFirstBySourceIdAndPeriodIdAndStatusOrderByIdDesc(Long sourceId, Long periodId, ImportStatus status);
}
