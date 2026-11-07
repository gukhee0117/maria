package com.app.maria.domain.stockpackage.vo;

import com.app.maria.domain.domestic.type.DomesticOrderSide;
import com.app.maria.domain.domestic.type.DomesticOrderStatus;
import com.app.maria.domain.domestic.type.Type;
import com.app.maria.domain.stockpackage.type.StockOrderRejectReason;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** domestic_order 한 행. ticker·name은 조회할 때만 붙는다. 거부된 주문은 price·amount가 null이고 수수료·거래세는 0이다. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockOrderVO {
    private Long domesticOrderId;
    private Long accountId;
    private Long domesticProductId;
    private Long stockPackageId;
    private DomesticOrderSide side;
    private BigDecimal qty;
    private BigDecimal price;
    private BigDecimal amount;
    private BigDecimal feeAmount;
    private BigDecimal taxAmount;
    private String channel;
    private String idempotencyKey;
    private DomesticOrderStatus status;
    private StockOrderRejectReason rejectReason;
    private Long securitiesTradeId;
    private LocalDateTime processedAt;

    private String ticker;
    private String name;
    private Type productType;
    private String market;

    /** 패키지 이름. 고객 단위 주문 내역을 조회할 때만 붙는다. */
    private String packageName;

    /** 증권사에 못 보낸 주문을 다시 보낼 때만 붙는다(고객 ci_hash). */
    private String ciHash;
}
