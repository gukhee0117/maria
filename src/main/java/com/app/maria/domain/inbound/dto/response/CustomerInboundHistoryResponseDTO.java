package com.app.maria.domain.inbound.dto.response;

import com.app.maria.domain.inbound.dto.InboundListDTO;
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

    public CustomerInboundHistoryResponseDTO(InboundListDTO dto) {
        this.inboundId = dto.getInboundId();
        this.foreignProductId = dto.getForeignProductId();
        this.ticker = dto.getTicker();
        this.name = dto.getProductName();
        this.requestedQty = dto.getRequestedQty();
        this.approvedQty = dto.getApprovedQty();
        this.processedAt = dto.getProcessedAt();
    }
}
