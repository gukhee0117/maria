package com.app.maria.domain.targetproduct.dto.response;

import com.app.maria.domain.targetproduct.dto.TargetProductJudgementFailureListDTO;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.*;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@ToString(exclude = "lastError")
@Builder
public class TargetProductJudgementFailureListResponseDTO {
    private Long failureId;
    private String customerName;
    private LocalDate tradeDate;
    private int failureCount;
    private String lastError;
    private LocalDateTime firstFailedAt;
    private LocalDateTime lastFailedAt;

    public TargetProductJudgementFailureListResponseDTO(TargetProductJudgementFailureListDTO dto) {
        this.failureId = dto.getFailureId();
        this.customerName = dto.getCustomerName();
        this.tradeDate = dto.getTradeDate();
        this.failureCount = dto.getFailureCount();
        this.lastError = dto.getLastError();
        this.firstFailedAt = dto.getFirstFailedAt();
        this.lastFailedAt = dto.getLastFailedAt();
    }
}
