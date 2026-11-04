package com.app.maria.global.client.kis;

import java.math.BigDecimal;

/** 국내주식 현재가 조회 결과. 가격은 모두 원 단위이며 KIS가 준 값을 그대로 담는다. */
public record DomesticQuote(
        String ticker,
        BigDecimal price,
        BigDecimal previousClose,
        BigDecimal upperLimit,
        BigDecimal lowerLimit) {}
