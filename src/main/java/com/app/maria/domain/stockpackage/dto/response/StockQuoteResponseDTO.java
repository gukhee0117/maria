package com.app.maria.domain.stockpackage.dto.response;

import com.app.maria.domain.stockpackage.vo.StockQuoteVO;
import java.math.BigDecimal;
import lombok.Getter;

@Getter
public class StockQuoteResponseDTO {
    private final String ticker;

    /** false면 시세를 받지 못한 것이다. 이 종목으로는 금액 계산·주문을 하지 않는다. */
    private final boolean available;

    private final BigDecimal price;
    private final BigDecimal previousClose;
    private final BigDecimal upperLimit;
    private final BigDecimal lowerLimit;

    public StockQuoteResponseDTO(StockQuoteVO vo) {
        this.ticker = vo.getTicker();
        this.available = vo.isAvailable();
        this.price = vo.getPrice();
        this.previousClose = vo.getPreviousClose();
        this.upperLimit = vo.getUpperLimit();
        this.lowerLimit = vo.getLowerLimit();
    }
}
