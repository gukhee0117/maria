package com.app.maria.domain.settlement.component;

import com.app.maria.domain.settlement.dto.SettlementBatchDTO;
import com.app.maria.domain.settlement.dto.SettlementItemDTO;
import com.app.maria.domain.settlement.mapper.SettlementBatchMapper;
import com.app.maria.domain.settlement.mapper.SettlementItemMapper;
import com.app.maria.domain.settlement.type.BatchStatus;
import com.app.maria.domain.settlement.type.SettlementFailureCode;
import com.app.maria.domain.settlement.type.SettlementItemResult;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class SettlementBatchStatusUpdater {
    private final SettlementBatchMapper settlementBatchMapper;
    private final SettlementItemMapper settlementItemMapper;
    private final BusinessClockService systemClock;

    @Transactional(
            transactionManager = "transactionManager",
            propagation = Propagation.REQUIRES_NEW)
    public void markFailedIfRunning(Long batchId, String failureMessage) {
        String limitedMessage = limitMessage(failureMessage);
        SettlementBatchDTO command =
                SettlementBatchDTO.builder()
                        .batchId(batchId)
                        .status(BatchStatus.FAILED)
                        .failureMessage(limitedMessage)
                        .build();

        int affectedRows = settlementBatchMapper.updateBatchStatus(command);
        if (affectedRows == 1) {
            settlementItemMapper.markPendingItemsFailed(
                    SettlementItemDTO.builder()
                            .batchId(batchId)
                            .result(SettlementItemResult.FAILED)
                            .processedAt(systemClock.now())
                            .failureCode(SettlementFailureCode.UNKNOWN_ERROR)
                            .failureMessage(limitedMessage)
                            .build());
            return;
        }

        BatchStatus currentStatus =
                settlementBatchMapper
                        .selectBatchById(batchId)
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorType.SETTLEMENT_STATE_CONFLICT,
                                                "Batch 실패 상태 변경 대상 없음 batchId=" + batchId))
                        .getStatus();
        if (currentStatus != BatchStatus.FAILED) {
            throw new AppException(
                    ErrorType.SETTLEMENT_STATE_CONFLICT, "Batch 실패 상태 변경 실패 batchId=" + batchId);
        }
    }

    public void startBatchRetry(Long batchId, String runId) {
        updateRetryRunning(batchId, runId);
    }

    public void startItemRetry(Long batchId) {
        updateRetryRunning(batchId, null);
    }

    public void completeFromLatestItems(Long batchId) {
        if (settlementBatchMapper.refreshBatchStatusAfterRetry(batchId) != 1) {
            throw new AppException(
                    ErrorType.SETTLEMENT_STATE_CONFLICT, "Batch 최종 상태 변경 실패 batchId=" + batchId);
        }
    }

    private void updateRetryRunning(Long batchId, String runId) {
        SettlementBatchDTO command =
                SettlementBatchDTO.builder().batchId(batchId).runId(runId).build();
        if (settlementBatchMapper.markBatchRetryRunning(command) != 1) {
            throw new AppException(
                    ErrorType.SETTLEMENT_STATE_CONFLICT, "Batch 재처리 상태 변경 실패 batchId=" + batchId);
        }
    }

    private String limitMessage(String failureMessage) {
        if (failureMessage == null || failureMessage.isBlank()) {
            return "확정산 Batch 실행 중 알 수 없는 오류가 발생했습니다.";
        }
        return failureMessage.substring(0, Math.min(failureMessage.length(), 500));
    }
}
