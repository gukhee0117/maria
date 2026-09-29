package com.app.maria.domain.tax.dto.response;

import com.app.maria.domain.tax.dto.TaxBatchHistoryDTO;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class TaxBatchHistoryResponseDTO {
    private Long jobExecutionId;
    private String runId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String status;
    private String exitStatus;
    private long readCount;
    private long writeCount;
    private long skipCount;

    public static TaxBatchHistoryResponseDTO of(TaxBatchHistoryDTO dto) {
        return TaxBatchHistoryResponseDTO.builder()
                .jobExecutionId(dto.getJobExecutionId())
                .runId(dto.getRunId())
                .startTime(dto.getStartTime())
                .endTime(dto.getEndTime())
                .status(dto.getStatus())
                .exitStatus(dto.getExitStatus())
                .readCount(dto.getReadCount())
                .writeCount(dto.getWriteCount())
                .skipCount(dto.getSkipCount())
                .build();
    }
}
