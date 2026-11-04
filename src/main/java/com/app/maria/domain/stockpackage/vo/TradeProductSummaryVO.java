package com.app.maria.domain.stockpackage.vo;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 고객의 체결 주문을 종목별로 모은 값. buyAmount는 수수료를 뺀 체결 매수 금액이다. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TradeProductSummaryVO {
    private Long domesticProductId;
    private String sectorName;
    private BigDecimal buyAmount;
    private int buyCount;
    private int sellCount;
}
