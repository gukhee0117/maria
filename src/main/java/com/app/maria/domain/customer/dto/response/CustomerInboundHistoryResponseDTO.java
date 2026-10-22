package com.app.maria.domain.customer.dto.response;

import com.app.maria.domain.foreignproduct.entity.ForeignProductEntity;
import com.app.maria.domain.inbound.entity.InboundEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;

@Getter
public class CustomerInboundHistoryResponseDTO {
    private final Long inboundId;
    private final Long foreignProductId;
    private final String ticker;
    private final String name;
    private final BigDecimal requestedQty;
    private final BigDecimal approvedQty;
    private final LocalDateTime processedAt;

    public CustomerInboundHistoryResponseDTO(InboundEntity inbound, ForeignProductEntity product) {
        this.inboundId = inbound.getInboundId();
        this.foreignProductId = product != null ? product.getForeignProductId() : null;
        this.ticker = product != null ? product.getTicker() : null;
        this.name = product != null ? product.getName() : null;
        this.requestedQty = inbound.getRequestedQty();
        this.approvedQty = inbound.getApprovedQty();
        this.processedAt = inbound.getProcessedAt();
    }
}
