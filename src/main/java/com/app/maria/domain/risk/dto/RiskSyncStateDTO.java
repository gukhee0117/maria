package com.app.maria.domain.risk.dto;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class RiskSyncStateDTO {
    private LocalDateTime nextPollAt;
    private LocalDateTime nextRetryAt;
    private int retryCount;
    private LocalDateTime lastAttemptAt;
    private LocalDateTime lastSuccessAt;
    private String lastErrorCode;
    private String lastRunKind;
}
