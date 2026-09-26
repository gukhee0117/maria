package com.app.maria.domain.tax.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class TaxBatchHistoryDTO {
    private Long jobExecutionId;
    private String runId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String status;
    private String exitStatus;
    private long readCount;
    private long writeCount;
    private long skipCount;
}
