package com.app.maria.domain.settlement.service;

import com.app.maria.domain.settlement.batch.SettlementBatchLauncher;
import com.app.maria.domain.settlement.component.SettlementBatchStatusUpdater;
import com.app.maria.domain.settlement.component.SettlementFailureRecorder;
import com.app.maria.domain.settlement.component.SettlementTransactionExecutor;
import com.app.maria.domain.settlement.dto.KrwExchangeDTO;
import com.app.maria.domain.settlement.dto.SettlementBatchDTO;
import com.app.maria.domain.settlement.dto.SettlementItemDTO;
import com.app.maria.domain.settlement.dto.SettlementJoinDTO;
import com.app.maria.domain.settlement.exception.*;
import com.app.maria.domain.settlement.mapper.KrwExchangeMapper;
import com.app.maria.domain.settlement.mapper.SettlementBatchGuardMapper;
import com.app.maria.domain.settlement.mapper.SettlementBatchMapper;
import com.app.maria.domain.settlement.mapper.SettlementItemMapper;
import com.app.maria.domain.settlement.mapper.SettlementJoinMapper;
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
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class SettlementServiceImpl implements SettlementService {
    private final KrwExchangeMapper krwExchangeMapper;
    private final SettlementItemMapper settlementItemMapper;
    private final SettlementBatchMapper settlementBatchMapper;
    private final SettlementJoinMapper settlementJoinMapper;
    private final SettlementBatchGuardMapper settlementBatchGuardMapper;
    private final PlatformTransactionManager transactionManager;
    private final SettlementBatchLauncher settlementBatchLauncher;
    private final BusinessClockService businessClockService;
    private final SettlementTransactionExecutor settlementTransactionExecutor;
    private final SettlementFailureRecorder settlementFailureRecorder;
    private final SettlementBatchStatusUpdater settlementBatchStatusUpdater;
    private final AuditActorProvider auditActorProvider;
    private final AuditLogService auditLogService;
    private final com.app.maria.domain.settlement.provider.ExchangeRateProvider
            exchangeRateProvider;

    @Override
    public SettlementBatchDTO executeSettlementBatch() {
        return executeSettlementBatch(null);
    }

    @Override
    public SettlementBatchDTO executeSettlementBatchByAdmin() {
        return executeSettlementBatch(auditActorProvider.getCurrentAdminId());
    }

    private SettlementBatchDTO executeSettlementBatch(Long adminId) {
        LocalDateTime executedAt = businessClockService.now();
        LocalDate businessDate = executedAt.toLocalDate();

        BatchLaunchResult result =
                new TransactionTemplate(transactionManager)
                        .execute(
                                status -> {
                                    settlementBatchGuardMapper.ensureGuard(businessDate);

                                    if (settlementBatchGuardMapper
                                            .selectGuardForUpdate(businessDate)
                                            .isEmpty()) {
                                        throw new SettlementBatchLockException(
                                                "확정산 Batch 업무일 잠금 획득 실패");
                                    }

                                    SettlementBatchDTO existingBatch =
                                            settlementBatchMapper
                                                    .selectBatchByBusinessDate(businessDate)
                                                    .orElse(null);
                                    if (existingBatch != null) {
                                        if (adminId != null) {
                                            logAudit(
                                                    adminId,
                                                    "SETTLEMENT_BATCH",
                                                    existingBatch.getBatchId(),
                                                    existingBatch.getStatus().name(),
                                                    existingBatch.getStatus().name(),
                                                    SettlementAuditLogReasonCode
                                                            .SETTLEMENT_BATCH_REQUESTED);
                                        }
                                        return new BatchLaunchResult(existingBatch, false);
                                    }

                                    SettlementBatchDTO newBatch =
                                            SettlementBatchDTO.builder()
                                                    .executedAt(executedAt)
                                                    .status(BatchStatus.RUNNING)
                                                    .runId(UUID.randomUUID().toString())
                                                    .build();

                                    if (settlementBatchMapper.insertBatch(newBatch) != 1
                                            || newBatch.getBatchId() == null) {
                                        throw new SettlementStateConflictException(
                                                "확정산 Batch 생성에 실패했습니다.");
                                    }

                                    settlementItemMapper.insertItemsForTargets(newBatch);
                                    if (adminId != null) {
                                        logAudit(
                                                adminId,
                                                "SETTLEMENT_BATCH",
                                                newBatch.getBatchId(),
                                                null,
                                                newBatch.getStatus().name(),
                                                SettlementAuditLogReasonCode
                                                        .SETTLEMENT_BATCH_REQUESTED);
                                    }
                                    return new BatchLaunchResult(newBatch, true);
                                });

        if (result == null) {
            throw new SettlementStateConflictException("확정산 Batch 트랜잭션 처리에 실패했습니다.");
        }
        if (result.shouldLaunch()) {
            launchBatch(result.batch());
        }
        return result.batch();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SettlementBatchDTO> getSettlementBatches() {
        return settlementBatchMapper.selectBatches();
    }

    @Override
    @Transactional(readOnly = true)
    public SettlementBatchDTO getSettlementBatch(Long batchId) {
        return settlementBatchMapper
                .selectBatchById(batchId)
                .orElseThrow(() -> new SettlementBatchNotFoundException("batch id로 배치 조회 실패"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SettlementJoinDTO> getSettlementBatchDetail(Long batchId) {
        return settlementJoinMapper.selectSettlementBatchDetail(batchId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SettlementJoinDTO> getSettlementBatchFailDetail(Long batchId) {
        return settlementJoinMapper.selectSettlementBatchFailDetail(batchId);
    }

    @Override
    @Transactional(readOnly = true)
    public SettlementBatchDTO getSettlementBatchByRunId(String runId) {
        return settlementBatchMapper
                .selectBatchByRunId(runId)
                .orElseThrow(() -> new SettlementBatchNotFoundException("run id로 배치 조회 실패"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SettlementItemDTO> getPendingSettlementItems(Long batchId, Long lastItemId) {
        getSettlementBatch(batchId);
        SettlementItemDTO cursor =
                SettlementItemDTO.builder().batchId(batchId).itemId(lastItemId).build();
        return settlementItemMapper.selectPendingItems(cursor);
    }

    @Override
    @Transactional(readOnly = true)
    public SettlementJoinDTO getSettlementItem(Long batchId, Long itemId) {
        getSettlementBatch(batchId);
        SettlementJoinDTO cursor =
                SettlementJoinDTO.builder().batchId(batchId).itemId(itemId).build();
        return settlementJoinMapper
                .selectItemDetail(cursor)
                .orElseThrow(() -> new SettlementItemNotFoundException("item detail 조회 실패"));
    }

    @Override
    public SettlementItemDTO retryFailedSettlementItem(Long batchId, Long itemId) {
        Long adminId = auditActorProvider.getCurrentAdminId();
        SettlementItemDTO retryItem =
                new TransactionTemplate(transactionManager)
                        .execute(
                                status -> {
                                    SettlementBatchDTO batch =
                                            settlementBatchMapper
                                                    .selectBatchByIdForUpdate(batchId)
                                                    .orElseThrow(
                                                            () ->
                                                                    new SettlementBatchNotFoundException(
                                                                            "재처리 대상 Batch를 찾을 수 없습니다."));
                                    if (batch.getStatus() != BatchStatus.FAILED) {
                                        throw new SettlementStateConflictException(
                                                "실패 상태 Batch의 Item만 재처리할 수 있습니다.");
                                    }
                                    SettlementItemDTO failedItem =
                                            settlementItemMapper
                                                    .selectItemByIdForUpdate(itemId)
                                                    .orElseThrow(
                                                            () ->
                                                                    new SettlementItemNotFoundException(
                                                                            "재처리 대상 정산 Item을 찾을 수 없습니다."));
                                    if (!batchId.equals(failedItem.getBatchId())
                                            || failedItem.getResult()
                                                    != SettlementItemResult.FAILED) {
                                        throw new SettlementStateConflictException(
                                                "실패한 정산 Item만 재처리할 수 있습니다.");
                                    }
                                    settlementItemMapper.selectItemsByExchangeIdForUpdate(
                                            failedItem.getExchangeId());
                                    if (settlementItemMapper.existsSuccessfulItemByExchangeId(
                                                    failedItem.getExchangeId())
                                            || settlementItemMapper.existsPendingItemByExchangeId(
                                                    failedItem.getExchangeId())) {
                                        throw new SettlementStateConflictException(
                                                "이미 성공 처리되었거나 재처리 중인 정산 건입니다.");
                                    }

                                    SettlementItemDTO command =
                                            SettlementItemDTO.builder()
                                                    .batchId(batchId)
                                                    .itemId(itemId)
                                                    .build();
                                    if (settlementItemMapper.insertRetryItem(command) != 1
                                            || command.getItemId() == null) {
                                        throw new SettlementStateConflictException(
                                                "정산 Item 재처리 이력 생성에 실패했습니다.");
                                    }
                                    settlementBatchStatusUpdater.startItemRetry(batchId);
                                    logAudit(
                                            adminId,
                                            "SETTLEMENT_ITEM",
                                            command.getItemId(),
                                            "FAILED / sourceItemId=" + itemId,
                                            "RETRY_REQUESTED / batchId=" + batchId,
                                            SettlementAuditLogReasonCode.SETTLEMENT_ITEM_RETRIED);
                                    return command;
                                });
        if (retryItem == null) {
            throw new SettlementStateConflictException("정산 Item 재처리 트랜잭션 처리에 실패했습니다.");
        }

        try {
            SettlementJoinDTO target =
                    settlementJoinMapper
                            .selectTargetByItemId(
                                    SettlementJoinDTO.builder()
                                            .batchId(batchId)
                                            .itemId(retryItem.getItemId())
                                            .build())
                            .orElseThrow(
                                    () ->
                                            new SettlementItemNotFoundException(
                                                    "재처리 대상 정산 정보를 찾을 수 없습니다."));
            SettlementBatchDTO batch =
                    settlementBatchMapper
                            .selectBatchById(batchId)
                            .orElseThrow(
                                    () ->
                                            new SettlementBatchNotFoundException(
                                                    "batch id로 배치 조회 실패"));
            if (target.getFinalAt() == null) {
                throw new SettlementStateConflictException(
                        "확정산 기준일이 없습니다. exchangeId=" + target.getExchangeId());
            }
            LocalDate rateDate = target.getFinalAt().toLocalDate();
            BigDecimal finalRate =
                    exchangeRateProvider.getFinalRate(target.getPurchaseCurrency(), rateDate);
            settlementTransactionExecutor.execute(target, finalRate);
        } catch (Exception exception) {
            settlementFailureRecorder.markFailed(retryItem.getItemId(), exception);
        }

        settlementBatchStatusUpdater.completeFromLatestItems(batchId);

        return settlementItemMapper
                .selectItemById(retryItem.getItemId())
                .orElseThrow(() -> new SettlementItemNotFoundException("재처리 Item 조회 실패"));
    }

    @Override
    public SettlementBatchDTO retryFailedSettlementBatch(Long batchId) {
        Long adminId = auditActorProvider.getCurrentAdminId();
        SettlementBatchDTO batch =
                new TransactionTemplate(transactionManager)
                        .execute(
                                status -> {
                                    SettlementBatchDTO foundBatch =
                                            settlementBatchMapper
                                                    .selectBatchByIdForUpdate(batchId)
                                                    .orElseThrow(
                                                            () ->
                                                                    new SettlementBatchNotFoundException(
                                                                            "재처리 대상 Batch를 찾을 수 없습니다."));
                                    if (foundBatch.getStatus() != BatchStatus.FAILED) {
                                        throw new SettlementStateConflictException(
                                                "실패 상태 Batch만 재처리할 수 있습니다.");
                                    }
                                    if (settlementItemMapper.insertRetryItemsForFailedBatch(batchId)
                                            == 0) {
                                        throw new SettlementStateConflictException(
                                                "재처리할 실패 정산 Item이 없습니다.");
                                    }
                                    String retryRunId = UUID.randomUUID().toString();
                                    settlementBatchStatusUpdater.startBatchRetry(
                                            batchId, retryRunId);
                                    foundBatch.setStatus(BatchStatus.RUNNING);
                                    foundBatch.setFailureMessage(null);
                                    foundBatch.setRunId(retryRunId);
                                    logAudit(
                                            adminId,
                                            "SETTLEMENT_BATCH",
                                            batchId,
                                            BatchStatus.FAILED.name(),
                                            BatchStatus.RUNNING.name(),
                                            SettlementAuditLogReasonCode.SETTLEMENT_BATCH_RETRIED);
                                    return foundBatch;
                                });
        if (batch == null) {
            throw new SettlementStateConflictException("정산 Batch 재처리 트랜잭션 처리에 실패했습니다.");
        }
        launchBatch(batch);
        return batch;
    }

    @Override
    @Transactional(readOnly = true)
    public KrwExchangeDTO getKrwExchange(Long exchangeId) {
        return krwExchangeMapper
                .selectExchangeById(exchangeId)
                .orElseThrow(() -> new KrwExchangeNotFoundException("환전 조회 실패"));
    }

    private record BatchLaunchResult(SettlementBatchDTO batch, boolean shouldLaunch) {}

    private void logAudit(
            Long adminId,
            String targetTable,
            Long targetId,
            String beforeValue,
            String afterValue,
            SettlementAuditLogReasonCode reasonCode) {
        auditLogService.log(
                AuditLogDTO.builder()
                        .adminId(adminId)
                        .targetTable(targetTable)
                        .targetPk(String.valueOf(targetId))
                        .beforeValue(beforeValue)
                        .afterValue(afterValue)
                        .reasonCode(reasonCode.name())
                        .build());
    }

    private void launchBatch(SettlementBatchDTO batch) {
        try {
            settlementBatchLauncher.launch(batch);
        } catch (TaskRejectedException exception) {
            markLaunchRejected(batch.getBatchId(), exception);
        }
    }

    private void markLaunchRejected(Long batchId, TaskRejectedException exception) {
        settlementBatchStatusUpdater.markFailedIfRunning(
                batchId, "확정산 Batch 작업 제출 실패: " + exception.getMessage());
        throw new SettlementStateConflictException("확정산 Batch 작업 제출에 실패했습니다.");
    }

    @Override
    @Transactional(readOnly = true)
    public int getProvisionalExchangeCount() {
        return krwExchangeMapper.countByStatus(SettlementStatus.PROVISIONAL);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal getPendingProvisionalAmount() {
        return krwExchangeMapper.sumProvisionalAmountByStatus(SettlementStatus.PROVISIONAL);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal getFinalizedAmountBetween(LocalDateTime start, LocalDateTime end) {
        return krwExchangeMapper.sumFinalizedAmountBetween(start, end);
    }
}
