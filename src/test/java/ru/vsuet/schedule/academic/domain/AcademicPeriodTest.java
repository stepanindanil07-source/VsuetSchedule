package ru.vsuet.schedule.academic.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.vsuet.schedule.common.WeekType;

class AcademicPeriodTest {

    /** The university's rule: Tue 1 Sep 2026 is in the first (numerator) week, which starts Mon 31 Aug. */
    private final AcademicPeriod period = new AcademicPeriod(2026, AcademicTerm.AUTUMN, LocalDate.of(2026, 9, 1),
            LocalDate.of(2027, 1, 31), LocalDate.of(2026, 8, 31), WeekType.NUMERATOR);

    @ParameterizedTest
    @CsvSource({
            "2026-08-31, NUMERATOR",     // Monday of week 1
            "2026-09-01, NUMERATOR",     // Tuesday, the first day of study
            "2026-09-06, NUMERATOR",     // Sunday of week 1
            "2026-09-07, DENOMINATOR",   // Monday of week 2
            "2026-09-13, DENOMINATOR",   // Sunday of week 2
            "2026-09-14, NUMERATOR",     // alternates again
            "2026-12-28, DENOMINATOR",   // far away: 17 weeks after the first
            "2026-08-30, DENOMINATOR"    // before the first week the pattern continues backwards
    })
    void weeksAlternateFromTheFirstWeek(LocalDate date, WeekType expected) {
        assertThat(period.weekTypeOn(date)).isEqualTo(expected);
    }

    @Test
    void firstWeekMayBeTheDenominator() {
        AcademicPeriod p = new AcademicPeriod(2026, AcademicTerm.AUTUMN, LocalDate.of(2026, 9, 1),
                LocalDate.of(2027, 1, 31), LocalDate.of(2026, 8, 31), WeekType.DENOMINATOR);

        assertThat(p.weekTypeOn(LocalDate.of(2026, 9, 3))).isEqualTo(WeekType.DENOMINATOR);
        assertThat(p.weekTypeOn(LocalDate.of(2026, 9, 8))).isEqualTo(WeekType.NUMERATOR);
    }

    @Test
    void everyWeekIsNotAValidFirstWeekType() {
        assertThatThrownBy(() -> new AcademicPeriod(2026, AcademicTerm.AUTUMN, LocalDate.of(2026, 9, 1),
                LocalDate.of(2027, 1, 31), LocalDate.of(2026, 8, 31), WeekType.EVERY_WEEK))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
