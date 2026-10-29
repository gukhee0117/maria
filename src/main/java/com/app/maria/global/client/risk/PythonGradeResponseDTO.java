package com.app.maria.global.client.risk;

import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record PythonGradeResponseDTO(
        String ticker,
        BigDecimal var975,
        Integer riskGrade,
        BigDecimal volatility,
        LocalDate priceFrom,
        LocalDate priceTo,
        OffsetDateTime asOf,
        Long analysisVersion) {
    public void validate() {
        if (ticker == null
                || !ticker.matches("[0-9]{6}")
                || !number(var975)
                || !number(volatility)
                || riskGrade == null
                || riskGrade < 1
                || riskGrade > 6
                || priceFrom == null
                || priceTo == null
                || priceFrom.isAfter(priceTo)
                || asOf == null
                || analysisVersion == null
                || analysisVersion < 1) throw new AppException(ErrorType.RISK_INVALID_RESPONSE);
    }

    private static boolean number(BigDecimal value) {
        return value != null
                && value.signum() >= 0
                && value.compareTo(new BigDecimal("999.9999")) <= 0
                && value.stripTrailingZeros().scale() <= 4;
    }
}
