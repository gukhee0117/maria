package com.app.maria.domain.stockpackage.vo;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** 종목 하나의 현재가 조회 결과. 시세를 받지 못했으면 available=false이고 가격은 null이다. */
@Getter
@AllArgsConstructor
public class StockQuoteVO {
    private final String ticker;
    private final boolean available;
    private final BigDecimal price;
    private final BigDecimal previousClose;
    private final BigDecimal upperLimit;
    private final BigDecimal lowerLimit;

    public static StockQuoteVO unavailable(String ticker) {
        return new StockQuoteVO(ticker, false, null, null, null, null);
    }
}
