package com.app.maria.domain.stockpackage.vo;

import com.app.maria.domain.domestic.type.DomesticStockStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** domestic_stock_balance 한 행(계좌 × 종목). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockBalanceVO {
    private Long domesticStockBalanceId;
    private Long accountId;
    private Long domesticProductId;
    private BigDecimal qty;
    private DomesticStockStatus status;
    private LocalDateTime lastPurchaseDate;
    private BigDecimal avgPurchasePrice;
}
