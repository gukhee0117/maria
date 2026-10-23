package com.app.maria.domain.settlement.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.settlement.dto.SettlementBatchDTO;
import com.app.maria.domain.settlement.dto.SettlementItemDTO;
import com.app.maria.domain.settlement.mapper.SettlementBatchMapper;
import com.app.maria.domain.settlement.mapper.SettlementItemMapper;
import com.app.maria.domain.settlement.type.BatchStatus;
import com.app.maria.domain.settlement.type.SettlementFailureCode;
import com.app.maria.domain.settlement.type.SettlementItemResult;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SettlementBatchStatusUpdaterTest {

    @Mock private SettlementBatchMapper settlementBatchMapper;
    @Mock private SettlementItemMapper settlementItemMapper;
    @Mock private BusinessClockService systemClock;

    @Test
    void marksRemainingPendingItemsAsFailedBeforeFailingBatch() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 9, 12, 0);
        when(systemClock.now()).thenReturn(now);
        when(settlementBatchMapper.updateBatchStatus(org.mockito.ArgumentMatchers.any()))
                .thenReturn(1);
        SettlementBatchStatusUpdater updater =
                new SettlementBatchStatusUpdater(
                        settlementBatchMapper, settlementItemMapper, systemClock);

        updater.markFailedIfRunning(1L, "Job 실행 실패");

        ArgumentCaptor<SettlementItemDTO> itemCaptor =
                ArgumentCaptor.forClass(SettlementItemDTO.class);
        verify(settlementItemMapper).markPendingItemsFailed(itemCaptor.capture());
        assertThat(itemCaptor.getValue())
                .extracting(
                        SettlementItemDTO::getBatchId,
                        SettlementItemDTO::getResult,
                        SettlementItemDTO::getProcessedAt,
                        SettlementItemDTO::getFailureCode,
                        SettlementItemDTO::getFailureMessage)
                .containsExactly(
                        1L,
                        SettlementItemResult.FAILED,
                        now,
                        SettlementFailureCode.UNKNOWN_ERROR,
                        "Job 실행 실패");

        ArgumentCaptor<SettlementBatchDTO> batchCaptor =
                ArgumentCaptor.forClass(SettlementBatchDTO.class);
        verify(settlementBatchMapper).updateBatchStatus(batchCaptor.capture());
        assertThat(batchCaptor.getValue().getStatus()).isEqualTo(BatchStatus.FAILED);
    }

    @Test
    void ignoresDuplicateFailureMarkWhenBatchIsAlreadyFailed() {
        when(settlementBatchMapper.updateBatchStatus(org.mockito.ArgumentMatchers.any()))
                .thenReturn(0);
        when(settlementBatchMapper.selectBatchById(1L))
                .thenReturn(
                        java.util.Optional.of(
                                SettlementBatchDTO.builder()
                                        .batchId(1L)
                                        .status(BatchStatus.FAILED)
                                        .build()));
        SettlementBatchStatusUpdater updater =
                new SettlementBatchStatusUpdater(
                        settlementBatchMapper, settlementItemMapper, systemClock);

        updater.markFailedIfRunning(1L, "Job 실행 실패");

        verify(settlementItemMapper, never())
                .markPendingItemsFailed(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsCompletionWhenBatchIsNoLongerRunning() {
        when(settlementBatchMapper.refreshBatchStatusAfterRetry(1L)).thenReturn(0);
        SettlementBatchStatusUpdater updater =
                new SettlementBatchStatusUpdater(
                        settlementBatchMapper, settlementItemMapper, systemClock);

        assertThatThrownBy(() -> updater.completeFromLatestItems(1L))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.SETTLEMENT_STATE_CONFLICT);
    }
}
