package ru.vsuet.schedule.academic.infrastructure;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.vsuet.schedule.academic.domain.AcademicPeriod;
import ru.vsuet.schedule.academic.domain.AcademicTerm;

public interface AcademicPeriodRepository extends JpaRepository<AcademicPeriod, Long> {

    /** The latest period that has already started (also covers the break between two semesters). */
    Optional<AcademicPeriod> findFirstByStartDateLessThanEqualOrderByStartDateDesc(java.time.LocalDate date);

    /** Used before the very first semester has started. */
    Optional<AcademicPeriod> findFirstByOrderByStartDateAsc();

    Optional<AcademicPeriod> findByYearFromAndTerm(short yearFrom, AcademicTerm term);
}
