package com.app.maria.domain.tax.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class HeldLotDTO {
    private Long inboundDetailId;
    private Long foreignProductId;
    private String ticker;
    private String market;
    private String currency;
    private BigDecimal purchaseFxRate;
    private BigDecimal purchasePrice;
    private BigDecimal currentQty;
    private String productLabel;
}
