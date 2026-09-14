package com.app.maria.domain.settlement.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.maria.domain.settlement.exception.SettlementHolidayCalendarException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class SettlementBusinessDayCalculatorTest {
    private final SettlementHolidayCalendar holidayCalendar = new SettlementHolidayCalendar();
    private final SettlementBusinessDayCalculator calculator =
            new SettlementBusinessDayCalculator(holidayCalendar);

    @Test
    void addsTwoBusinessDaysFromWeekday() {
        LocalDateTime finalAt = calculator.calculateFinalAt(LocalDateTime.of(2026, 8, 10, 15, 30));

        assertThat(finalAt).isEqualTo(LocalDateTime.of(2026, 8, 12, 0, 0));
    }

    @Test
    void skipsWeekendAndSubstituteHoliday() {
        LocalDateTime finalAt = calculator.calculateFinalAt(LocalDateTime.of(2026, 8, 14, 15, 30));

        assertThat(finalAt).isEqualTo(LocalDateTime.of(2026, 8, 19, 0, 0));
    }

    @Test
    void skipsChuseokHolidays() {
        LocalDateTime finalAt = calculator.calculateFinalAt(LocalDateTime.of(2026, 9, 23, 15, 30));

        assertThat(finalAt).isEqualTo(LocalDateTime.of(2026, 9, 29, 0, 0));
    }

    @Test
    void calculatesAcrossYearBoundary() {
        LocalDateTime finalAt = calculator.calculateFinalAt(LocalDateTime.of(2026, 12, 31, 15, 30));

        assertThat(finalAt).isEqualTo(LocalDateTime.of(2027, 1, 5, 0, 0));
    }

    @Test
    void calculatesAcrossMayMonthBoundary() {
        LocalDateTime finalAt = calculator.calculateFinalAt(LocalDateTime.of(2026, 5, 31, 15, 30));

        assertThat(finalAt).isEqualTo(LocalDateTime.of(2026, 6, 2, 0, 0));
    }

    @Test
    void calculatesAcrossJulyMonthBoundary() {
        LocalDateTime finalAt = calculator.calculateFinalAt(LocalDateTime.of(2026, 7, 31, 15, 30));

        assertThat(finalAt).isEqualTo(LocalDateTime.of(2026, 8, 4, 0, 0));
    }

    @Test
    void rejectsDateWhenHolidayYearIsNotConfigured() {
        assertThatThrownBy(() -> holidayCalendar.isHoliday(LocalDate.of(2028, 1, 3)))
                .isInstanceOf(SettlementHolidayCalendarException.class)
                .hasMessage("정산 휴일 정보가 없습니다. year=2028");
    }
}
