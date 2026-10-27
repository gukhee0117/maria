package com.app.maria.domain.settlement.dto.response;

import com.app.maria.domain.settlement.event.SettlementCompletedEvent;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record SettlementNotificationResponseDTO(
        Long batchId,
        Long itemId,
        Long accountId,
        Long exchangeId,
        BigDecimal finalAmount,
        LocalDateTime settledAt) {

    public static SettlementNotificationResponseDTO from(SettlementCompletedEvent event) {
        return new SettlementNotificationResponseDTO(
                event.batchId(),
                event.itemId(),
                event.accountId(),
                event.exchangeId(),
                event.finalAmount(),
                event.settledAt());
    }
}
