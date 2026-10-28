package com.app.maria.domain.outbound.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;

@Getter
public class CustomerOutboundResponseDTO {
    private final BigDecimal totalOutboundQty;
    private final int lotCount;
    private final LocalDateTime processedAt;

    public CustomerOutboundResponseDTO(
            BigDecimal totalOutboundQty, int lotCount, LocalDateTime processedAt) {
        this.totalOutboundQty = totalOutboundQty;
        this.lotCount = lotCount;
        this.processedAt = processedAt;
    }
}
