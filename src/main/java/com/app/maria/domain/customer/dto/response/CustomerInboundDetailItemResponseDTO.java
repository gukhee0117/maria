package com.app.maria.domain.customer.dto.response;

import com.app.maria.domain.inbound.entity.InboundDetailEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;

@Getter
public class CustomerInboundDetailItemResponseDTO {
    private final Long inboundDetailId;
    private final String sourceBroker;
    private final BigDecimal approvedQty;
    private final BigDecimal currentQty;
    private final LocalDateTime purchaseDate;
    private final BigDecimal purchasePrice;
    private final String purchaseCurrency;
    private final BigDecimal purchaseFxRate;

    public CustomerInboundDetailItemResponseDTO(InboundDetailEntity detail) {
        this.inboundDetailId = detail.getInboundDetailId();
        this.sourceBroker = detail.getSourceBroker();
        this.approvedQty = detail.getQty();
        this.currentQty = detail.getCurrentQty();
        this.purchaseDate = detail.getPurchaseDate();
        this.purchasePrice = detail.getPurchasePrice();
        this.purchaseCurrency = detail.getPurchaseCurrency();
        this.purchaseFxRate = detail.getPurchaseFxRate();
    }
}
