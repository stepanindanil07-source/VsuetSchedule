package ru.vsuet.schedule.importer.infrastructure;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import ru.vsuet.schedule.importer.domain.ScheduleSource;

public interface ScheduleSourceRepository extends JpaRepository<ScheduleSource, Long> {

    List<ScheduleSource> findByEnabledTrueOrderById();

    /** SELECT ... FOR UPDATE: two imports of the same source cannot apply their results at the same time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ScheduleSource s where s.id = :id")
    Optional<ScheduleSource> findForUpdateById(@Param("id") Long id);

    @Transactional
    @Modifying
    @Query("update ScheduleSource s set s.lastCheckedAt = :now where s.id = :id")
    void touchChecked(@Param("id") Long id, @Param("now") Instant now);
}
