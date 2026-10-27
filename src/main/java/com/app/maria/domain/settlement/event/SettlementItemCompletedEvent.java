package com.app.maria.domain.settlement.event;

import java.time.LocalDateTime;

public record SettlementItemCompletedEvent(
        Long batchId, Long accountId, LocalDateTime completedAt) {}
