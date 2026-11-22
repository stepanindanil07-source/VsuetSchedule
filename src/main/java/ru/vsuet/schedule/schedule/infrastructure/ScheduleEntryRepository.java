package ru.vsuet.schedule.schedule.infrastructure;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.vsuet.schedule.schedule.domain.ScheduleEntry;

public interface ScheduleEntryRepository extends JpaRepository<ScheduleEntry, Long> {

    List<ScheduleEntry> findByGroupIdAndPeriodId(Long groupId, Long periodId);

    /** Bulk delete: no entity loading, runs as one statement inside the caller's transaction. */
    @Modifying(flushAutomatically = true)
    @Query("delete from ScheduleEntry e where e.groupId = :groupId and e.periodId = :periodId")
    int deleteByGroupAndPeriod(@Param("groupId") Long groupId, @Param("periodId") Long periodId);
}
