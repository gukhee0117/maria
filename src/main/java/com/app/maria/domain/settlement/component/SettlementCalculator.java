package com.app.maria.domain.settlement.component;

import com.app.maria.domain.settlement.type.ProvisionalRate;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
public class SettlementCalculator {
    private static final int FOREIGN_SCALE = 8;
    private static final int KRW_SCALE = 0;
    private static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;

    public BigDecimal calculateFinalAmount(
            BigDecimal provisionalAmount, BigDecimal settlementFxRate, BigDecimal finalRate) {
        validatePositive(provisionalAmount, "provisionalAmount");
        validatePositive(settlementFxRate, "settlementFxRate");
        validatePositive(finalRate, "finalRate");

        BigDecimal provisionalRate =
                settlementFxRate.multiply(ProvisionalRate.PROVISIONAL_RATE.getValue());

        BigDecimal foreignAmount =
                provisionalAmount.divide(provisionalRate, FOREIGN_SCALE, ROUNDING_MODE);

        return foreignAmount.multiply(finalRate).setScale(KRW_SCALE, ROUNDING_MODE);
    }

    private void validatePositive(BigDecimal value, String fieldName) {
        if (value == null) {
            throw new AppException(
                    ErrorType.SETTLEMENT_CALCULATION_FAILED, fieldName + " - 요청 값 오류");
        }
        if (value.signum() <= 0) {
            throw new AppException(
                    ErrorType.SETTLEMENT_CALCULATION_FAILED, fieldName + "은 0보다 커야 합니다.");
        }
    }
}
