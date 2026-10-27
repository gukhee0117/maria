package com.app.maria.domain.settlement.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record SettlementCompletedEvent(
        Long batchId,
        Long itemId,
        Long accountId,
        Long exchangeId,
        BigDecimal finalAmount,
        LocalDateTime settledAt) {}
