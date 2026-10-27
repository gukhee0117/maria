package com.app.maria.domain.settlement.event;

import java.time.LocalDateTime;

public record SettlementBatchCompletedEvent(Long batchId, LocalDateTime completedAt) {}
