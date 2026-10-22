package com.app.maria.domain.customer.dto.response;

import com.app.maria.domain.foreignproduct.entity.ForeignProductEntity;
import com.app.maria.domain.inbound.entity.InboundDetailEntity;
import com.app.maria.domain.inbound.entity.InboundEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;

@Getter
public class CustomerInboundDetailResponseDTO {
    private final Long inboundId;
    private final Long foreignProductId;
    private final String ticker;
    private final String name;
    private final Long sourceGeneralAccountId;
    private final BigDecimal requestedQty;
    private final BigDecimal approvedQty;
    private final String partialReason;
    private final LocalDateTime processedAt;
    private final List<CustomerInboundDetailItemResponseDTO> details;

    public CustomerInboundDetailResponseDTO(
            InboundEntity inbound,
            ForeignProductEntity product,
            List<InboundDetailEntity> detailEntities) {
        this.inboundId = inbound.getInboundId();
        this.foreignProductId = product != null ? product.getForeignProductId() : null;
        this.ticker = product != null ? product.getTicker() : null;
        this.name = product != null ? product.getName() : null;
        this.sourceGeneralAccountId = detailEntities.isEmpty()
                ? null
                : detailEntities.get(0).getSourceGeneralAccountId();
        this.requestedQty = inbound.getRequestedQty();
        this.approvedQty = inbound.getApprovedQty();
        this.partialReason = inbound.getApprovedQty().compareTo(inbound.getRequestedQty()) < 0
                ? "BELOW_REQUEST"
                : null;
        this.processedAt = inbound.getProcessedAt();
        this.details = detailEntities.stream()
                .map(CustomerInboundDetailItemResponseDTO::new)
                .toList();
    }
}
