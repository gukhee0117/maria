package com.app.maria.domain.stockpackage.dto.response;

import java.math.BigDecimal;

/**
 * 주문 창에서 예상 금액을 보여줄 때 쓰는 오늘 기준 요율(%). 수수료율은 계좌 개설 후 1년 안이면 우대율이고, 증권거래세율은 코스피·코스닥 주식을 팔 때만 붙는다. 실제
 * 금액은 체결할 때 서버가 같은 규칙으로 다시 계산한다.
 */
public record StockOrderFeeResponseDTO(
        BigDecimal commissionRatePercent, BigDecimal securitiesTaxRatePercent) {}
