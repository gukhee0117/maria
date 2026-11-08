package com.app.maria.domain.notification.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class SettlementEmailDetails {
    private BigDecimal settledAmount;
    private LocalDateTime completedAt;

    public SettlementEmailDetails(BigDecimal settledAmount, LocalDateTime completedAt) {
        this.settledAmount = settledAmount;
        this.completedAt = completedAt;
    }
}
