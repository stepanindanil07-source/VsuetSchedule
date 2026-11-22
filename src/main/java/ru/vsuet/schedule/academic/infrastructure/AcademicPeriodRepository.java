package ru.vsuet.schedule.academic.infrastructure;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.vsuet.schedule.academic.domain.AcademicPeriod;
import ru.vsuet.schedule.academic.domain.AcademicTerm;

public interface AcademicPeriodRepository extends JpaRepository<AcademicPeriod, Long> {

    /** The latest period that has already started (also covers the break between two semesters). */
    Optional<AcademicPeriod> findFirstByStartDateLessThanEqualOrderByStartDateDesc(java.time.LocalDate date);

    /** Used before the very first semester has started. */
    Optional<AcademicPeriod> findFirstByOrderByStartDateAsc();

    Optional<AcademicPeriod> findByYearFromAndTerm(short yearFrom, AcademicTerm term);

    /** Periods whose days intersect {@code start..end} (both inclusive). */
    @Query("select p from AcademicPeriod p where p.startDate <= :end and p.endDate >= :start order by p.startDate")
    List<AcademicPeriod> findOverlapping(@Param("start") java.time.LocalDate start, @Param("end") java.time.LocalDate end);
}
