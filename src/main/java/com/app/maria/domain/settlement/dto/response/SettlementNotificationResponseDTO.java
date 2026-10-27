package com.app.maria.domain.settlement.dto.response;

import com.app.maria.domain.settlement.event.SettlementBatchCompletedEvent;
import java.time.LocalDateTime;

public record SettlementNotificationResponseDTO(Long batchId, LocalDateTime completedAt) {

    public static SettlementNotificationResponseDTO from(SettlementBatchCompletedEvent event) {
        return new SettlementNotificationResponseDTO(event.batchId(), event.completedAt());
    }
}
