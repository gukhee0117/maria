package com.app.maria.domain.fundflow.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 자금 환류 지표의 비율 계산 공통 규칙: 소수 4자리, 분모가 없거나 0이면 null. */
final class FundFlowRatios {

    static final int RATIO_SCALE = 4;

    private FundFlowRatios() {}

    static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return null;
        }
        return numerator.divide(denominator, RATIO_SCALE, RoundingMode.HALF_UP);
    }
}
