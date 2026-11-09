package com.app.maria.domain.fundflow.type;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FundFlowPeriodTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 11, 17);

    @Test
    @DisplayName("ALL은 하한이 없다")
    void allHasNoLowerBound() {
        assertThat(FundFlowPeriod.ALL.startOf(AS_OF)).isNull();
    }

    @Test
    @DisplayName("MONTH는 기준일이 속한 달의 1일부터다")
    void monthStartsAtFirstDayOfMonth() {
        assertThat(FundFlowPeriod.MONTH.startOf(AS_OF)).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    @DisplayName("QUARTER는 기준일이 속한 분기의 첫날부터다")
    void quarterStartsAtFirstDayOfQuarter() {
        assertThat(FundFlowPeriod.QUARTER.startOf(AS_OF)).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(FundFlowPeriod.QUARTER.startOf(LocalDate.of(2026, 3, 31)))
                .isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(FundFlowPeriod.QUARTER.startOf(LocalDate.of(2026, 7, 1)))
                .isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(FundFlowPeriod.QUARTER.startOf(LocalDate.of(2026, 12, 31)))
                .isEqualTo(LocalDate.of(2026, 10, 1));
    }
}
