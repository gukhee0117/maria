package com.app.maria.domain.domestic.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** 고객 화면용 국내 매매 체결 한 건. name은 종목 마스터에 없으면 null, amount는 수량 × 체결가이다. */
@Getter
@AllArgsConstructor
public class CustomerDomesticTradeResponseDTO {
    private final LocalDateTime executedAt;
    private final String stockCode;
    private final String name;
    private final String tradeType;
    private final BigDecimal qty;
    private final BigDecimal price;
    private final BigDecimal amount;
}
