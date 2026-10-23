package com.app.maria.domain.settlement.component;

import com.app.maria.domain.settlement.type.ProvisionalRate;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/** 매도 체결 시점의 원화 기준 금액으로 가환전액을 계산한다. */
@Component
public class ProvisionalExchangeCalculator {
    private static final int KRW_SCALE = 0;

    public BigDecimal calculate(BigDecimal sellQty, BigDecimal basePrice) {
        validatePositive(sellQty, "sellQty");
        validatePositive(basePrice, "basePrice");
        return sellQty.multiply(basePrice)
                .multiply(ProvisionalRate.PROVISIONAL_RATE.getValue())
                .setScale(KRW_SCALE, RoundingMode.HALF_UP);
    }

    private void validatePositive(BigDecimal value, String fieldName) {
        if (value == null || value.signum() <= 0) {
            throw new AppException(
                    ErrorType.SETTLEMENT_CALCULATION_FAILED, fieldName + "은 0보다 커야 합니다.");
        }
    }
}
