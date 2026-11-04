package com.app.maria.domain.sellorder.dto.response;

import java.math.BigDecimal;

/**
 * 고객 화면용 매도 한도 현황. remainingLimit은 타사 RIA 누적 매도와 합산 5천만원 상한까지 반영한 값이라 limitAmount − usedAmount보다 작을
 * 수 있다.
 */
public record SellLimitStatusDTO(
        BigDecimal limitAmount, BigDecimal usedAmount, BigDecimal remainingLimit) {}
