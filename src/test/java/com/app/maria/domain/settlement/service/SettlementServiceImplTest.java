package com.app.maria.domain.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.settlement.batch.SettlementBatchLauncher;
import com.app.maria.domain.settlement.component.SettlementBatchStatusUpdater;
import com.app.maria.domain.settlement.component.SettlementFailureRecorder;
import com.app.maria.domain.settlement.component.SettlementTransactionExecutor;
import com.app.maria.domain.settlement.dto.KrwExchangeDTO;
import com.app.maria.domain.settlement.dto.SettlementBatchDTO;
import com.app.maria.domain.settlement.dto.SettlementItemDTO;
import com.app.maria.domain.settlement.dto.SettlementJoinDTO;
import com.app.maria.domain.settlement.exception.KrwExchangeNotFoundException;
import com.app.maria.domain.settlement.exception.SettlementBatchNotFoundException;
import com.app.maria.domain.settlement.exception.SettlementItemNotFoundException;
import com.app.maria.domain.settlement.exception.SettlementStateConflictException;
import com.app.maria.domain.settlement.mapper.KrwExchangeMapper;
import com.app.maria.domain.settlement.mapper.SettlementBatchGuardMapper;
import com.app.maria.domain.settlement.mapper.SettlementBatchMapper;
import com.app.maria.domain.settlement.mapper.SettlementItemMapper;
import com.app.maria.domain.settlement.mapper.SettlementJoinMapper;
import com.app.maria.domain.settlement.provider.ExchangeRateProvider;
import com.app.maria.domain.settlement.type.BatchStatus;
import com.app.maria.domain.settlement.type.SettlementAuditLogReasonCode;
import com.app.maria.domain.settlement.type.SettlementItemResult;
import com.app.maria.domain.settlement.type.SettlementStatus;
import com.app.maria.global.audit.dto.AuditLogDTO;
import com.app.maria.global.audit.provider.AuditActorProvider;
import com.app.maria.global.audit.service.AuditLogService;
import com.app.maria.global.clock.service.BusinessClockService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class SettlementServiceImplTest {

    private static final Long BATCH_ID = 1L;
    private static final Long ITEM_ID = 10L;
    private static final Long EXCHANGE_ID = 100L;
    private static final Long ADMIN_ID = 99L;
    private static final String RUN_ID = "run-1";

    @Mock private KrwExchangeMapper krwExchangeMapper;

    @Mock private SettlementItemMapper settlementItemMapper;

    @Mock private SettlementBatchMapper settlementBatchMapper;

    @Mock private SettlementJoinMapper settlementJoinMapper;

    @Mock private SettlementBatchGuardMapper settlementBatchGuardMapper;

    @Mock private PlatformTransactionManager transactionManager;

    @Mock private TransactionStatus transactionStatus;

    @Mock private SettlementBatchLauncher settlementBatchLauncher;
    @Mock private BusinessClockService businessClockService;
    @Mock private SettlementTransactionExecutor settlementTransactionExecutor;
    @Mock private SettlementFailureRecorder settlementFailureRecorder;
    @Mock private SettlementBatchStatusUpdater settlementBatchStatusUpdater;
    @Mock private ExchangeRateProvider exchangeRateProvider;
    @Mock private AuditActorProvider auditActorProvider;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private SettlementServiceImpl settlementService;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient()
                .when(auditActorProvider.getCurrentAdminId())
                .thenReturn(ADMIN_ID);
    }

    @Test
    @DisplayName("Guard 잠금 후 Batch와 Snapshot을 같은 트랜잭션에서 생성한다")
    void executeSettlementBatchCreatesBatchAfterGuardLock() {
        LocalDate businessDate = LocalDate.of(2026, 8, 9);
        when(businessClockService.now()).thenReturn(businessDate.atStartOfDay());
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(settlementBatchGuardMapper.selectGuardForUpdate(businessDate))
                .thenReturn(Optional.of(businessDate));
        when(settlementBatchMapper.selectBatchByBusinessDate(businessDate))
                .thenReturn(Optional.empty());
        doAnswer(
                        invocation -> {
                            SettlementBatchDTO batch = invocation.getArgument(0);
                            batch.setBatchId(BATCH_ID);
                            return 1;
                        })
                .when(settlementBatchMapper)
                .insertBatch(any(SettlementBatchDTO.class));

        SettlementBatchDTO result = settlementService.executeSettlementBatchByAdmin();

        assertThat(result.getBatchId()).isEqualTo(BATCH_ID);
        assertThat(result.getStatus()).isEqualTo(BatchStatus.RUNNING);
        verify(settlementBatchGuardMapper).ensureGuard(businessDate);
        verify(settlementBatchGuardMapper).selectGuardForUpdate(businessDate);
        verify(settlementBatchMapper).selectBatchByBusinessDate(businessDate);
        verify(settlementItemMapper).insertItemsForTargets(result);
        verify(transactionManager).commit(transactionStatus);
        verify(settlementBatchLauncher).launch(result);
        assertAuditLog(
                "SETTLEMENT_BATCH",
                BATCH_ID,
                null,
                "RUNNING",
                SettlementAuditLogReasonCode.SETTLEMENT_BATCH_REQUESTED);
    }

    @Test
    @DisplayName("동일 업무일 Batch가 있으면 기존 Batch를 반환하고 새 Job을 실행하지 않는다")
    void executeSettlementBatchReturnsExistingBatchForSameBusinessDate() {
        LocalDate businessDate = LocalDate.of(2026, 8, 9);
        when(businessClockService.now()).thenReturn(businessDate.atStartOfDay());
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(settlementBatchGuardMapper.selectGuardForUpdate(businessDate))
                .thenReturn(Optional.of(businessDate));
        SettlementBatchDTO existingBatch = batch();
        when(settlementBatchMapper.selectBatchByBusinessDate(businessDate))
                .thenReturn(Optional.of(existingBatch));

        SettlementBatchDTO result = settlementService.executeSettlementBatch();

        assertThat(result).isSameAs(existingBatch);
        verify(settlementBatchMapper, never()).insertBatch(any());
        verify(settlementItemMapper, never()).insertItemsForTargets(any());
        verify(transactionManager).commit(transactionStatus);
        verify(settlementBatchLauncher, never()).launch(any());
    }

    @Test
    void adminExecutionRequestForExistingBatchWritesAuditLog() {
        LocalDate businessDate = LocalDate.of(2026, 8, 9);
        SettlementBatchDTO existingBatch = batch();
        existingBatch.setStatus(BatchStatus.COMPLETED);
        when(businessClockService.now()).thenReturn(businessDate.atStartOfDay());
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(settlementBatchGuardMapper.selectGuardForUpdate(businessDate))
                .thenReturn(Optional.of(businessDate));
        when(settlementBatchMapper.selectBatchByBusinessDate(businessDate))
                .thenReturn(Optional.of(existingBatch));

        SettlementBatchDTO result = settlementService.executeSettlementBatchByAdmin();

        assertThat(result).isSameAs(existingBatch);
        verify(settlementBatchLauncher, never()).launch(any());
        assertAuditLog(
                "SETTLEMENT_BATCH",
                BATCH_ID,
                "COMPLETED",
                "COMPLETED",
                SettlementAuditLogReasonCode.SETTLEMENT_BATCH_REQUESTED);
    }

    @Test
    @DisplayName("비동기 작업 제출이 거절되면 Batch를 실패 처리한다")
    void executeSettlementBatchMarksFailedWhenAsyncSubmissionIsRejected() {
        LocalDate businessDate = LocalDate.of(2026, 8, 9);
        when(businessClockService.now()).thenReturn(businessDate.atStartOfDay());
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(settlementBatchGuardMapper.selectGuardForUpdate(businessDate))
                .thenReturn(Optional.of(businessDate));
        when(settlementBatchMapper.selectBatchByBusinessDate(businessDate))
                .thenReturn(Optional.empty());
        doAnswer(
                        invocation -> {
                            invocation.<SettlementBatchDTO>getArgument(0).setBatchId(BATCH_ID);
                            return 1;
                        })
                .when(settlementBatchMapper)
                .insertBatch(any(SettlementBatchDTO.class));
        org.mockito.Mockito.doThrow(new TaskRejectedException("queue full"))
                .when(settlementBatchLauncher)
                .launch(any());

        assertThatThrownBy(() -> settlementService.executeSettlementBatch())
                .isInstanceOf(SettlementStateConflictException.class)
                .hasMessage("확정산 Batch 작업 제출에 실패했습니다.");

        verify(settlementBatchStatusUpdater)
                .markFailedIfRunning(BATCH_ID, "확정산 Batch 작업 제출 실패: queue full");
    }

    @Test
    @DisplayName("실패 Batch 재처리는 실패 Item 이력을 생성하고 비동기 Job을 다시 실행한다")
    void retryFailedSettlementBatchRetriesOnlyFailedItems() {
        SettlementBatchDTO failedBatch = batch();
        failedBatch.setStatus(BatchStatus.FAILED);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(settlementBatchMapper.selectBatchByIdForUpdate(BATCH_ID))
                .thenReturn(Optional.of(failedBatch));
        when(settlementItemMapper.insertRetryItemsForFailedBatch(BATCH_ID)).thenReturn(2);

        SettlementBatchDTO result = settlementService.retryFailedSettlementBatch(BATCH_ID);

        assertThat(result.getStatus()).isEqualTo(BatchStatus.RUNNING);
        verify(settlementBatchStatusUpdater).startBatchRetry(eq(BATCH_ID), anyString());
        verify(settlementBatchLauncher).launch(any(SettlementBatchDTO.class));
        assertAuditLog(
                "SETTLEMENT_BATCH",
                BATCH_ID,
                "FAILED",
                "RUNNING",
                SettlementAuditLogReasonCode.SETTLEMENT_BATCH_RETRIED);
    }

    @Test
    @DisplayName("개별 Item 재처리는 Batch를 실행 중으로 전환해 Batch 재시도와 경합하지 않는다")
    void retryFailedSettlementItemMarksBatchRunning() {
        SettlementBatchDTO failedBatch = batch();
        failedBatch.setStatus(BatchStatus.FAILED);
        failedBatch.setExecutedAt(LocalDateTime.of(2026, 8, 9, 9, 0));
        SettlementItemDTO failedItem = item();
        failedItem.setResult(SettlementItemResult.FAILED);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(settlementBatchMapper.selectBatchByIdForUpdate(BATCH_ID))
                .thenReturn(Optional.of(failedBatch));
        when(settlementItemMapper.selectItemByIdForUpdate(ITEM_ID))
                .thenReturn(Optional.of(failedItem));
        when(settlementItemMapper.existsSuccessfulItemByExchangeId(EXCHANGE_ID)).thenReturn(false);
        when(settlementItemMapper.existsPendingItemByExchangeId(EXCHANGE_ID)).thenReturn(false);
        doAnswer(
                        invocation -> {
                            invocation.<SettlementItemDTO>getArgument(0).setItemId(11L);
                            return 1;
                        })
                .when(settlementItemMapper)
                .insertRetryItem(any(SettlementItemDTO.class));
        when(settlementJoinMapper.selectTargetByItemId(any()))
                .thenReturn(
                        Optional.of(
                                SettlementJoinDTO.builder()
                                        .itemId(11L)
                                        .batchId(BATCH_ID)
                                        .exchangeId(EXCHANGE_ID)
                                        .accountId(1L)
                                        .purchaseCurrency("USD")
                                        .finalAt(LocalDateTime.of(2026, 8, 7, 0, 0))
                                        .build()));
        when(settlementBatchMapper.selectBatchById(BATCH_ID)).thenReturn(Optional.of(failedBatch));
        when(exchangeRateProvider.getFinalRate("USD", LocalDate.of(2026, 8, 7)))
                .thenReturn(new BigDecimal("1400"));
        when(settlementItemMapper.selectItemById(11L))
                .thenReturn(
                        Optional.of(
                                SettlementItemDTO.builder()
                                        .itemId(11L)
                                        .batchId(BATCH_ID)
                                        .result(SettlementItemResult.SUCCESS)
                                        .build()));

        SettlementItemDTO result = settlementService.retryFailedSettlementItem(BATCH_ID, ITEM_ID);

        assertThat(result.getResult()).isEqualTo(SettlementItemResult.SUCCESS);
        verify(settlementBatchStatusUpdater).startItemRetry(BATCH_ID);
        verify(settlementBatchStatusUpdater).completeFromLatestItems(BATCH_ID);
        assertAuditLog(
                "SETTLEMENT_ITEM",
                11L,
                "FAILED / sourceItemId=10",
                "RETRY_REQUESTED / batchId=1",
                SettlementAuditLogReasonCode.SETTLEMENT_ITEM_RETRIED);
    }

    @Test
    @DisplayName("확정산 Batch 목록을 Mapper 조회 결과 그대로 반환한다")
    void getSettlementBatchesReturnsMapperResult() {
        List<SettlementBatchDTO> batches = List.of(batch());
        when(settlementBatchMapper.selectBatches()).thenReturn(batches);

        List<SettlementBatchDTO> result = settlementService.getSettlementBatches();

        assertThat(result).isSameAs(batches);
        verify(settlementBatchMapper).selectBatches();
    }

    @Test
    @DisplayName("batchId에 해당하는 Batch를 반환한다")
    void getSettlementBatchReturnsBatch() {
        SettlementBatchDTO batch = batch();
        when(settlementBatchMapper.selectBatchById(BATCH_ID)).thenReturn(Optional.of(batch));

        SettlementBatchDTO result = settlementService.getSettlementBatch(BATCH_ID);

        assertThat(result).isSameAs(batch);
    }

    @Test
    @DisplayName("batchId에 해당하는 Batch가 없으면 NotFound 예외를 반환한다")
    void getSettlementBatchThrowsWhenBatchDoesNotExist() {
        when(settlementBatchMapper.selectBatchById(BATCH_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> settlementService.getSettlementBatch(BATCH_ID))
                .isInstanceOf(SettlementBatchNotFoundException.class)
                .hasMessage("batch id로 배치 조회 실패");
    }

    @Test
    @DisplayName("runId에 해당하는 Batch를 반환한다")
    void getSettlementBatchByRunIdReturnsBatch() {
        SettlementBatchDTO batch = batch();
        when(settlementBatchMapper.selectBatchByRunId(RUN_ID)).thenReturn(Optional.of(batch));

        SettlementBatchDTO result = settlementService.getSettlementBatchByRunId(RUN_ID);

        assertThat(result).isSameAs(batch);
    }

    @Test
    @DisplayName("runId에 해당하는 Batch가 없으면 NotFound 예외를 반환한다")
    void getSettlementBatchByRunIdThrowsWhenBatchDoesNotExist() {
        when(settlementBatchMapper.selectBatchByRunId(RUN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> settlementService.getSettlementBatchByRunId(RUN_ID))
                .isInstanceOf(SettlementBatchNotFoundException.class)
                .hasMessage("run id로 배치 조회 실패");
    }

    @Test
    @DisplayName("대기 Item 조회 cursor를 Mapper에 전달한다")
    void getPendingSettlementItemsPassesCursorToMapper() {
        SettlementBatchDTO batch = batch();
        List<SettlementItemDTO> items = List.of(item());
        when(settlementBatchMapper.selectBatchById(BATCH_ID)).thenReturn(Optional.of(batch));
        when(settlementItemMapper.selectPendingItems(any(SettlementItemDTO.class)))
                .thenReturn(items);

        List<SettlementItemDTO> result = settlementService.getPendingSettlementItems(BATCH_ID, 0L);

        assertThat(result).isSameAs(items);
        ArgumentCaptor<SettlementItemDTO> cursorCaptor =
                ArgumentCaptor.forClass(SettlementItemDTO.class);
        verify(settlementItemMapper).selectPendingItems(cursorCaptor.capture());
        assertThat(cursorCaptor.getValue().getBatchId()).isEqualTo(BATCH_ID);
        assertThat(cursorCaptor.getValue().getItemId()).isZero();
    }

    @Test
    @DisplayName("Batch가 없으면 대기 Item Mapper를 호출하지 않는다")
    void getPendingSettlementItemsChecksBatchBeforeItems() {
        when(settlementBatchMapper.selectBatchById(BATCH_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> settlementService.getPendingSettlementItems(BATCH_ID, 0L))
                .isInstanceOf(SettlementBatchNotFoundException.class);

        verify(settlementItemMapper, never()).selectPendingItems(any());
    }

    @Test
    @DisplayName("batchId와 itemId가 일치하는 Item 상세를 반환한다")
    void getSettlementItemReturnsItemDetail() {
        SettlementJoinDTO detail = itemDetail();
        when(settlementBatchMapper.selectBatchById(BATCH_ID)).thenReturn(Optional.of(batch()));
        when(settlementJoinMapper.selectItemDetail(any(SettlementJoinDTO.class)))
                .thenReturn(Optional.of(detail));

        SettlementJoinDTO result = settlementService.getSettlementItem(BATCH_ID, ITEM_ID);

        assertThat(result).isSameAs(detail);
        ArgumentCaptor<SettlementJoinDTO> queryCaptor =
                ArgumentCaptor.forClass(SettlementJoinDTO.class);
        verify(settlementJoinMapper).selectItemDetail(queryCaptor.capture());
        assertThat(queryCaptor.getValue().getBatchId()).isEqualTo(BATCH_ID);
        assertThat(queryCaptor.getValue().getItemId()).isEqualTo(ITEM_ID);
    }

    @Test
    @DisplayName("Batch가 없으면 Item 상세 Mapper를 호출하지 않는다")
    void getSettlementItemChecksBatchBeforeDetail() {
        when(settlementBatchMapper.selectBatchById(BATCH_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> settlementService.getSettlementItem(BATCH_ID, ITEM_ID))
                .isInstanceOf(SettlementBatchNotFoundException.class);

        verify(settlementJoinMapper, never()).selectItemDetail(any());
    }

    @Test
    @DisplayName("batchId와 itemId에 해당하는 상세가 없으면 NotFound 예외를 반환한다")
    void getSettlementItemThrowsWhenDetailDoesNotExist() {
        when(settlementBatchMapper.selectBatchById(BATCH_ID)).thenReturn(Optional.of(batch()));
        when(settlementJoinMapper.selectItemDetail(any(SettlementJoinDTO.class)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> settlementService.getSettlementItem(BATCH_ID, ITEM_ID))
                .isInstanceOf(SettlementItemNotFoundException.class)
                .hasMessage("item detail 조회 실패");
    }

    @Test
    @DisplayName("exchangeId에 해당하는 원화 환전을 반환한다")
    void getKrwExchangeReturnsExchange() {
        KrwExchangeDTO exchange = exchange();
        when(krwExchangeMapper.selectExchangeById(EXCHANGE_ID)).thenReturn(Optional.of(exchange));

        KrwExchangeDTO result = settlementService.getKrwExchange(EXCHANGE_ID);

        assertThat(result).isSameAs(exchange);
    }

    @Test
    @DisplayName("exchangeId에 해당하는 환전이 없으면 NotFound 예외를 반환한다")
    void getKrwExchangeThrowsWhenExchangeDoesNotExist() {
        when(krwExchangeMapper.selectExchangeById(EXCHANGE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> settlementService.getKrwExchange(EXCHANGE_ID))
                .isInstanceOf(KrwExchangeNotFoundException.class)
                .hasMessage("환전 조회 실패");
    }

    @Test
    @DisplayName("가환전 대기금액은 PROVISIONAL 상태 합계를 그대로 반환한다")
    void getPendingProvisionalAmountReturnsMapperSum() {
        when(krwExchangeMapper.sumProvisionalAmountByStatus(SettlementStatus.PROVISIONAL))
                .thenReturn(new BigDecimal("2900000.00"));

        BigDecimal result = settlementService.getPendingProvisionalAmount();

        assertThat(result).isEqualByComparingTo("2900000.00");
    }

    @Test
    @DisplayName("확정산 완료금액은 주어진 기간 합계를 그대로 반환한다")
    void getFinalizedAmountBetweenReturnsMapperSum() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 9, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 8, 10, 0, 0);
        when(krwExchangeMapper.sumFinalizedAmountBetween(start, end))
                .thenReturn(new BigDecimal("110000.00"));

        BigDecimal result = settlementService.getFinalizedAmountBetween(start, end);

        assertThat(result).isEqualByComparingTo("110000.00");
    }

    private SettlementBatchDTO batch() {
        return SettlementBatchDTO.builder()
                .batchId(BATCH_ID)
                .runId(RUN_ID)
                .status(BatchStatus.RUNNING)
                .build();
    }

    private SettlementItemDTO item() {
        return SettlementItemDTO.builder()
                .itemId(ITEM_ID)
                .batchId(BATCH_ID)
                .exchangeId(EXCHANGE_ID)
                .build();
    }

    private SettlementJoinDTO itemDetail() {
        return SettlementJoinDTO.builder()
                .itemId(ITEM_ID)
                .batchId(BATCH_ID)
                .exchangeId(EXCHANGE_ID)
                .build();
    }

    private KrwExchangeDTO exchange() {
        return KrwExchangeDTO.builder()
                .exchangeId(EXCHANGE_ID)
                .accountId(1L)
                .settlementStatus(SettlementStatus.PROVISIONAL)
                .build();
    }

    private void assertAuditLog(
            String targetTable,
            Long targetId,
            String beforeValue,
            String afterValue,
            SettlementAuditLogReasonCode reasonCode) {
        ArgumentCaptor<AuditLogDTO> captor = ArgumentCaptor.forClass(AuditLogDTO.class);
        verify(auditLogService).log(captor.capture());
        AuditLogDTO auditLog = captor.getValue();
        assertThat(auditLog.getAdminId()).isEqualTo(ADMIN_ID);
        assertThat(auditLog.getTargetTable()).isEqualTo(targetTable);
        assertThat(auditLog.getTargetPk()).isEqualTo(String.valueOf(targetId));
        assertThat(auditLog.getBeforeValue()).isEqualTo(beforeValue);
        assertThat(auditLog.getAfterValue()).isEqualTo(afterValue);
        assertThat(auditLog.getReasonCode()).isEqualTo(reasonCode.name());
    }
}
