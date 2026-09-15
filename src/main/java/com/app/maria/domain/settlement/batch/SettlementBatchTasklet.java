package com.app.maria.domain.settlement.batch;

import com.app.maria.domain.settlement.component.SettlementBatchStatusUpdater;
import com.app.maria.domain.settlement.component.SettlementFailureRecorder;
import com.app.maria.domain.settlement.component.SettlementTransactionExecutor;
import com.app.maria.domain.settlement.dto.SettlementBatchDTO;
import com.app.maria.domain.settlement.dto.SettlementItemDTO;
import com.app.maria.domain.settlement.dto.SettlementJoinDTO;
import com.app.maria.domain.settlement.exception.ExchangeRateExternalApiException;
import com.app.maria.domain.settlement.exception.SettlementBatchNotFoundException;
import com.app.maria.domain.settlement.exception.SettlementStateConflictException;
import com.app.maria.domain.settlement.mapper.SettlementBatchMapper;
import com.app.maria.domain.settlement.mapper.SettlementItemMapper;
import com.app.maria.domain.settlement.mapper.SettlementJoinMapper;
import com.app.maria.domain.settlement.provider.ExchangeRateProvider;
import com.app.maria.domain.settlement.type.BatchStatus;
import com.app.maria.global.exception.ExchangeRateNotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SettlementBatchTasklet implements Tasklet {

    private static final String LAST_ITEM_ID = "settlement.lastItemId";
    private static final String RATE_CACHE_PREFIX = "settlement.rate.";
    private static final long INITIAL_ITEM_ID = 0L;

    private final SettlementBatchMapper settlementBatchMapper;
    private final SettlementItemMapper settlementItemMapper;
    private final SettlementJoinMapper settlementJoinMapper;
    private final ExchangeRateProvider exchangeRateProvider;
    private final SettlementTransactionExecutor settlementTransactionExecutor;
    private final SettlementFailureRecorder settlementFailureRecorder;
    private final SettlementBatchStatusUpdater settlementBatchStatusUpdater;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Object batchParameter = chunkContext.getStepContext().getJobParameters().get("batchId");
        Long batchId =
                batchParameter instanceof Number ? ((Number) batchParameter).longValue() : null;
        Object runParameter = chunkContext.getStepContext().getJobParameters().get("runId");
        String runId = runParameter instanceof String ? (String) runParameter : null;
        if (batchId == null || runId == null || runId.isBlank()) {
            throw new SettlementStateConflictException("확정산 Job Parameter가 올바르지 않습니다.");
        }

        SettlementBatchDTO batch =
                settlementBatchMapper
                        .selectBatchById(batchId)
                        .orElseThrow(
                                () ->
                                        new SettlementBatchNotFoundException(
                                                "Batch를 찾을 수 없습니다. batchId=" + batchId));
        if (batch.getStatus() != BatchStatus.RUNNING || !runId.equals(batch.getRunId())) {
            throw new SettlementStateConflictException(
                    "확정산 Batch 상태가 실행 가능하지 않습니다. batchId=" + batchId);
        }

        var executionContext =
                chunkContext.getStepContext().getStepExecution().getExecutionContext();
        long lastItemId = restoreLastItemId(executionContext);
        SettlementItemDTO cursor =
                SettlementItemDTO.builder().batchId(batchId).itemId(lastItemId).build();
        List<SettlementItemDTO> items = settlementItemMapper.selectPendingItems(cursor);

        if (items.isEmpty()) {
            int pendingCount = settlementItemMapper.countPendingItems(batchId);
            if (pendingCount > 0 && lastItemId > INITIAL_ITEM_ID) {
                executionContext.remove(LAST_ITEM_ID);
                return RepeatStatus.CONTINUABLE;
            }
            finalizeBatch(batchId, pendingCount);
            return RepeatStatus.FINISHED;
        }

        for (SettlementItemDTO item : items) {
            processItem(batchId, item, executionContext);
            executionContext.putLong(LAST_ITEM_ID, item.getItemId());
        }

        return RepeatStatus.CONTINUABLE;
    }

    private void processItem(
            Long batchId, SettlementItemDTO item, ExecutionContext executionContext) {
        try {
            SettlementJoinDTO query =
                    SettlementJoinDTO.builder().batchId(batchId).itemId(item.getItemId()).build();
            Optional<SettlementJoinDTO> target = settlementJoinMapper.selectTargetByItemId(query);
            if (target.isEmpty()) {
                throw new SettlementStateConflictException(
                        "확정산 대상을 찾을 수 없습니다. itemId=" + item.getItemId());
            }

            SettlementJoinDTO value = target.get();
            LocalDate rateDate = requireFinalDate(value);
            BigDecimal finalRate =
                    resolveFinalRate(value.getPurchaseCurrency(), rateDate, executionContext);
            settlementTransactionExecutor.execute(value, finalRate);
        } catch (Exception e) {
            settlementFailureRecorder.markFailed(item.getItemId(), e);
        }
    }

    private LocalDate requireFinalDate(SettlementJoinDTO target) {
        if (target.getFinalAt() == null) {
            throw new SettlementStateConflictException(
                    "확정산 기준일이 없습니다. exchangeId=" + target.getExchangeId());
        }
        return target.getFinalAt().toLocalDate();
    }

    private BigDecimal resolveFinalRate(
            String currency, LocalDate rateDate, ExecutionContext executionContext) {
        String cacheKey = RATE_CACHE_PREFIX + currency + ":" + rateDate;
        String valueKey = cacheKey + ".value";
        String failureTypeKey = cacheKey + ".failureType";
        String failureMessageKey = cacheKey + ".failureMessage";

        BigDecimal cachedRate = restoreCachedRate(executionContext, valueKey);
        if (cachedRate != null) {
            clearFailureCache(executionContext, failureTypeKey, failureMessageKey);
            return cachedRate;
        }
        RuntimeException cachedFailure =
                restoreCachedFailure(executionContext, failureTypeKey, failureMessageKey);
        if (cachedFailure != null) {
            throw cachedFailure;
        }

        try {
            BigDecimal rate = exchangeRateProvider.getFinalRate(currency, rateDate);
            clearFailureCache(executionContext, failureTypeKey, failureMessageKey);
            executionContext.putString(valueKey, rate.toPlainString());
            return rate;
        } catch (ExchangeRateNotFoundException e) {
            cacheFailure(
                    executionContext, valueKey, failureTypeKey, failureMessageKey, "NOT_FOUND", e);
            throw e;
        } catch (ExchangeRateExternalApiException e) {
            cacheFailure(
                    executionContext,
                    valueKey,
                    failureTypeKey,
                    failureMessageKey,
                    "EXTERNAL_API",
                    e);
            throw e;
        }
    }

    private long restoreLastItemId(ExecutionContext executionContext) {
        if (!executionContext.containsKey(LAST_ITEM_ID)) {
            return INITIAL_ITEM_ID;
        }

        Object value = executionContext.get(LAST_ITEM_ID);
        if (value instanceof Long itemId && itemId >= INITIAL_ITEM_ID) {
            return itemId;
        }

        executionContext.remove(LAST_ITEM_ID);
        return INITIAL_ITEM_ID;
    }

    private BigDecimal restoreCachedRate(ExecutionContext executionContext, String valueKey) {
        if (!executionContext.containsKey(valueKey)) {
            return null;
        }

        Object value = executionContext.get(valueKey);
        if (value instanceof String rateValue) {
            try {
                BigDecimal rate = new BigDecimal(rateValue);
                if (rate.signum() > 0) {
                    return rate;
                }
            } catch (NumberFormatException ignored) {
                // 손상된 캐시는 제거한 뒤 외부 환율을 다시 조회한다.
            }
        }

        executionContext.remove(valueKey);
        return null;
    }

    private RuntimeException restoreCachedFailure(
            ExecutionContext executionContext, String failureTypeKey, String failureMessageKey) {
        boolean hasFailureType = executionContext.containsKey(failureTypeKey);
        boolean hasFailureMessage = executionContext.containsKey(failureMessageKey);
        if (!hasFailureType && !hasFailureMessage) {
            return null;
        }

        Object failureType = executionContext.get(failureTypeKey);
        Object failureMessage = executionContext.get(failureMessageKey);
        if (failureType instanceof String type
                && failureMessage instanceof String message
                && !message.isBlank()) {
            if ("NOT_FOUND".equals(type)) {
                return new ExchangeRateNotFoundException(message);
            }
            if ("EXTERNAL_API".equals(type)) {
                return new ExchangeRateExternalApiException(message, null);
            }
        }

        clearFailureCache(executionContext, failureTypeKey, failureMessageKey);
        return null;
    }

    private void cacheFailure(
            ExecutionContext executionContext,
            String valueKey,
            String failureTypeKey,
            String failureMessageKey,
            String failureType,
            Exception exception) {
        executionContext.remove(valueKey);
        executionContext.putString(failureTypeKey, failureType);
        executionContext.putString(
                failureMessageKey,
                exception.getMessage() == null ? "환율 조회 실패" : exception.getMessage());
    }

    private void clearFailureCache(
            ExecutionContext executionContext, String failureTypeKey, String failureMessageKey) {
        executionContext.remove(failureTypeKey);
        executionContext.remove(failureMessageKey);
    }

    private void finalizeBatch(Long batchId, int pendingCount) {
        if (pendingCount > 0) {
            throw new SettlementStateConflictException(
                    "미처리 Item이 남아 있어 Batch를 종료할 수 없습니다. batchId="
                            + batchId
                            + ", pending="
                            + pendingCount);
        }

        settlementBatchStatusUpdater.completeFromLatestItems(batchId);
    }
}
