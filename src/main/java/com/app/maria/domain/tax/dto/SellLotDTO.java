package com.app.maria.domain.tax.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SellLotDTO {
    private Long accountId;
    private Long orderId;
    private Long inboundDetailId;
    private BigDecimal purchaseFxRate;
    private BigDecimal purchasePrice;
    private BigDecimal sellQty;
    private LocalDate finalAt;
    private BigDecimal finalAmount;
    private String productLabel;
}
