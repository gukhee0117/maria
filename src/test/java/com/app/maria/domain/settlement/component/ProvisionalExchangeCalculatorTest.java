package com.app.maria.domain.settlement.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ProvisionalExchangeCalculatorTest {
    private final ProvisionalExchangeCalculator calculator = new ProvisionalExchangeCalculator();

    @Test
    void calculatesProvisionalAmountWithOnePercentAdjustment() {
        BigDecimal provisionalAmount =
                calculator.calculate(new BigDecimal("10"), new BigDecimal("270000"));

        assertThat(provisionalAmount).isEqualByComparingTo("2673000");
        assertThat(provisionalAmount.scale()).isZero();
    }

    @Test
    void roundsProvisionalAmountToKrwUnitUsingHalfUp() {
        BigDecimal provisionalAmount =
                calculator.calculate(new BigDecimal("1"), new BigDecimal("100.51"));

        assertThat(provisionalAmount).isEqualByComparingTo("100");
        assertThat(provisionalAmount.scale()).isZero();
    }

    @Test
    void rejectsMissingOrNonPositiveInputs() {
        assertThatThrownBy(() -> calculator.calculate(null, BigDecimal.ONE))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.SETTLEMENT_CALCULATION_FAILED);
        assertThatThrownBy(() -> calculator.calculate(BigDecimal.ZERO, BigDecimal.ONE))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.SETTLEMENT_CALCULATION_FAILED);
        assertThatThrownBy(() -> calculator.calculate(BigDecimal.ONE, BigDecimal.ZERO))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.SETTLEMENT_CALCULATION_FAILED);
    }
}
