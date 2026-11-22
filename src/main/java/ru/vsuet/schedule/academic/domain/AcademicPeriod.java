package ru.vsuet.schedule.academic.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import ru.vsuet.schedule.common.WeekType;

/**
 * A semester. {@code firstWeekStart} (a Monday) and {@code firstWeekType} are entered by the university staff;
 * the parity of any other week follows from them because weeks alternate.
 */
@Entity
@Table(name = "academic_period")
public class AcademicPeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "year_from")
    private short yearFrom;

    @Column(name = "year_to")
    private short yearTo;

    @Enumerated(EnumType.STRING)
    private AcademicTerm term;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "first_week_start")
    private LocalDate firstWeekStart;

    @Enumerated(EnumType.STRING)
    @Column(name = "first_week_type")
    private WeekType firstWeekType;

    protected AcademicPeriod() {
    }

    public AcademicPeriod(int yearFrom, AcademicTerm term, LocalDate startDate, LocalDate endDate,
                          LocalDate firstWeekStart, WeekType firstWeekType) {
        if (firstWeekType == WeekType.EVERY_WEEK) {
            throw new IllegalArgumentException("The first week must be NUMERATOR or DENOMINATOR");
        }
        this.yearFrom = (short) yearFrom;
        this.yearTo = (short) (yearFrom + 1);
        this.term = term;
        this.startDate = startDate;
        this.endDate = endDate;
        this.firstWeekStart = firstWeekStart;
        this.firstWeekType = firstWeekType;
    }

    /** Changes the dates and the week-parity anchor; the identity (term and year) stays. */
    public void reschedule(LocalDate startDate, LocalDate endDate, LocalDate firstWeekStart, WeekType firstWeekType) {
        if (firstWeekType == WeekType.EVERY_WEEK) {
            throw new IllegalArgumentException("The first week must be NUMERATOR or DENOMINATOR");
        }
        this.startDate = startDate;
        this.endDate = endDate;
        this.firstWeekStart = firstWeekStart;
        this.firstWeekType = firstWeekType;
    }

    /**
     * Parity of the week that contains {@code date}. Weeks alternate starting from {@code firstWeekStart};
     * dates before it follow the same pattern backwards.
     */
    public WeekType weekTypeOn(LocalDate date) {
        long weeksSinceFirst = Math.floorDiv(ChronoUnit.DAYS.between(firstWeekStart, date), 7L);
        boolean sameAsFirst = Math.floorMod(weeksSinceFirst, 2L) == 0;
        if (sameAsFirst) {
            return firstWeekType;
        }
        return firstWeekType == WeekType.NUMERATOR ? WeekType.DENOMINATOR : WeekType.NUMERATOR;
    }

    public Long getId() {
        return id;
    }

    public int getYearFrom() {
        return yearFrom;
    }

    public int getYearTo() {
        return yearTo;
    }

    public AcademicTerm getTerm() {
        return term;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public LocalDate getFirstWeekStart() {
        return firstWeekStart;
    }

    public WeekType getFirstWeekType() {
        return firstWeekType;
    }
}
