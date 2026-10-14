package com.app.maria.domain.targetproduct.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.*;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@Builder
public class TargetProductJudgementFailureListDTO {
    private Long failureId;
    private String customerName;
    private LocalDate tradeDate;
    private int failureCount;
    private String lastError;
    private LocalDateTime firstFailedAt;
    private LocalDateTime lastFailedAt;
}
