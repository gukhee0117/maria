package com.app.maria.domain.settlement.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.settlement.component.SettlementBatchStatusUpdater;
import com.app.maria.domain.settlement.component.SettlementFailureRecorder;
import com.app.maria.domain.settlement.component.SettlementTransactionExecutor;
import com.app.maria.domain.settlement.dto.SettlementBatchDTO;
import com.app.maria.domain.settlement.dto.SettlementItemDTO;
import com.app.maria.domain.settlement.dto.SettlementJoinDTO;
import com.app.maria.domain.settlement.exception.ExchangeRateExternalApiException;
import com.app.maria.domain.settlement.exception.SettlementStateConflictException;
import com.app.maria.domain.settlement.mapper.SettlementBatchMapper;
import com.app.maria.domain.settlement.mapper.SettlementItemMapper;
import com.app.maria.domain.settlement.mapper.SettlementJoinMapper;
import com.app.maria.domain.settlement.provider.ExchangeRateProvider;
import com.app.maria.domain.settlement.type.BatchStatus;
import com.app.maria.global.exception.ExchangeRateNotFoundException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.repeat.RepeatStatus;

@ExtendWith(MockitoExtension.class)
class SettlementBatchTaskletTest {

    @Mock private SettlementBatchMapper settlementBatchMapper;
    @Mock private SettlementItemMapper settlementItemMapper;
    @Mock private SettlementJoinMapper settlementJoinMapper;
    @Mock private ExchangeRateProvider exchangeRateProvider;
    @Mock private SettlementTransactionExecutor settlementTransactionExecutor;
    @Mock private SettlementFailureRecorder settlementFailureRecorder;
    @Mock private SettlementBatchStatusUpdater settlementBatchStatusUpdater;
    @Mock private StepContribution contribution;

    private StepExecution stepExecution;
    private SettlementBatchTasklet tasklet;

    @BeforeEach
    void setUp() {
        tasklet =
                new SettlementBatchTasklet(
                        settlementBatchMapper,
                        settlementItemMapper,
                        settlementJoinMapper,
                        exchangeRateProvider,
                        settlementTransactionExecutor,
                        settlementFailureRecorder,
                        settlementBatchStatusUpdater);
        JobParameters jobParameters =
                new JobParametersBuilder()
                        .addLong("batchId", 1L)
                        .addString("runId", "run-1")
                        .toJobParameters();
        JobExecution jobExecution = new JobExecution(1L, jobParameters);
        stepExecution = new StepExecution("settlementStep", jobExecution);
    }

    @Test
    void processesPageAndStoresLastItemId() {
        SettlementBatchDTO batch = batch();
        SettlementItemDTO item = SettlementItemDTO.builder().itemId(10L).batchId(1L).build();
        SettlementJoinDTO target =
                SettlementJoinDTO.builder()
                        .itemId(10L)
                        .batchId(1L)
                        .purchaseCurrency("USD")
                        .finalAt(LocalDateTime.of(2026, 8, 4, 0, 0))
                        .build();
        when(settlementBatchMapper.selectBatchById(1L)).thenReturn(Optional.of(batch));
        when(settlementItemMapper.selectPendingItems(any())).thenReturn(List.of(item));
        when(settlementJoinMapper.selectTargetByItemId(any())).thenReturn(Optional.of(target));
        when(exchangeRateProvider.getFinalRate(
                        "USD", LocalDateTime.of(2026, 8, 4, 9, 0).toLocalDate()))
                .thenReturn(new BigDecimal("1400"));

        RepeatStatus status =
                tasklet.execute(contribution, new ChunkContext(new StepContext(stepExecution)));

        assertThat(status).isEqualTo(RepeatStatus.CONTINUABLE);
        assertThat(stepExecution.getExecutionContext().getLong("settlement.lastItemId"))
                .isEqualTo(10L);
        verify(settlementTransactionExecutor).execute(target, new BigDecimal("1400"));
        verify(settlementFailureRecorder, never()).markFailed(any(), any());
    }

    @Test
    void completesBatchWhenNoPendingItemsRemain() {
        when(settlementBatchMapper.selectBatchById(1L)).thenReturn(Optional.of(batch()));
        when(settlementItemMapper.selectPendingItems(any())).thenReturn(List.of());
        when(settlementItemMapper.countPendingItems(1L)).thenReturn(0);

        RepeatStatus status =
                tasklet.execute(contribution, new ChunkContext(new StepContext(stepExecution)));

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        verify(settlementBatchStatusUpdater).completeFromLatestItems(1L);
    }

    @Test
    void resetsCorruptedLastItemIdAndRestartsFromInitialCursor() {
        stepExecution.getExecutionContext().putString("settlement.lastItemId", "corrupted");
        when(settlementBatchMapper.selectBatchById(1L)).thenReturn(Optional.of(batch()));
        when(settlementItemMapper.selectPendingItems(any())).thenReturn(List.of());
        when(settlementItemMapper.countPendingItems(1L)).thenReturn(0);

        tasklet.execute(contribution, new ChunkContext(new StepContext(stepExecution)));

        ArgumentCaptor<SettlementItemDTO> cursor = ArgumentCaptor.forClass(SettlementItemDTO.class);
        verify(settlementItemMapper).selectPendingItems(cursor.capture());
        assertThat(cursor.getValue().getItemId()).isZero();
        assertThat(stepExecution.getExecutionContext().containsKey("settlement.lastItemId"))
                .isFalse();
    }

    @Test
    void resetsValidButStaleCursorWhenPendingItemsWereSkipped() {
        stepExecution.getExecutionContext().putLong("settlement.lastItemId", 999L);
        when(settlementBatchMapper.selectBatchById(1L)).thenReturn(Optional.of(batch()));
        when(settlementItemMapper.selectPendingItems(any())).thenReturn(List.of());
        when(settlementItemMapper.countPendingItems(1L)).thenReturn(1);

        RepeatStatus status =
                tasklet.execute(contribution, new ChunkContext(new StepContext(stepExecution)));

        assertThat(status).isEqualTo(RepeatStatus.CONTINUABLE);
        assertThat(stepExecution.getExecutionContext().containsKey("settlement.lastItemId"))
                .isFalse();
        verify(settlementBatchStatusUpdater, never()).completeFromLatestItems(any());
    }

    @Test
    void removesMalformedRateCacheAndFetchesRateAgain() {
        String valueKey = "settlement.rate.USD:2026-08-04.value";
        stepExecution.getExecutionContext().putString(valueKey, "not-a-number");
        SettlementItemDTO item = SettlementItemDTO.builder().itemId(10L).batchId(1L).build();
        SettlementJoinDTO target = target(10L);
        when(settlementBatchMapper.selectBatchById(1L)).thenReturn(Optional.of(batch()));
        when(settlementItemMapper.selectPendingItems(any())).thenReturn(List.of(item));
        when(settlementJoinMapper.selectTargetByItemId(any())).thenReturn(Optional.of(target));
        when(exchangeRateProvider.getFinalRate("USD", target.getFinalAt().toLocalDate()))
                .thenReturn(new BigDecimal("1400"));

        tasklet.execute(contribution, new ChunkContext(new StepContext(stepExecution)));

        verify(exchangeRateProvider).getFinalRate("USD", target.getFinalAt().toLocalDate());
        verify(settlementTransactionExecutor).execute(target, new BigDecimal("1400"));
        assertThat(stepExecution.getExecutionContext().getString(valueKey)).isEqualTo("1400");
    }

    @Test
    void removesBlankRateCacheAndFetchesRateAgain() {
        String valueKey = "settlement.rate.USD:2026-08-04.value";
        stepExecution.getExecutionContext().putString(valueKey, "   ");
        SettlementItemDTO item = SettlementItemDTO.builder().itemId(10L).batchId(1L).build();
        SettlementJoinDTO target = target(10L);
        when(settlementBatchMapper.selectBatchById(1L)).thenReturn(Optional.of(batch()));
        when(settlementItemMapper.selectPendingItems(any())).thenReturn(List.of(item));
        when(settlementJoinMapper.selectTargetByItemId(any())).thenReturn(Optional.of(target));
        when(exchangeRateProvider.getFinalRate("USD", target.getFinalAt().toLocalDate()))
                .thenReturn(new BigDecimal("1400"));

        tasklet.execute(contribution, new ChunkContext(new StepContext(stepExecution)));

        verify(exchangeRateProvider).getFinalRate("USD", target.getFinalAt().toLocalDate());
        verify(settlementTransactionExecutor).execute(target, new BigDecimal("1400"));
        assertThat(stepExecution.getExecutionContext().getString(valueKey)).isEqualTo("1400");
    }

    @Test
    void removesIncompleteFailureCacheAndFetchesRateAgain() {
        String cacheKey = "settlement.rate.USD:2026-08-04";
        stepExecution.getExecutionContext().putString(cacheKey + ".failureType", "NOT_FOUND");
        SettlementItemDTO item = SettlementItemDTO.builder().itemId(10L).batchId(1L).build();
        SettlementJoinDTO target = target(10L);
        when(settlementBatchMapper.selectBatchById(1L)).thenReturn(Optional.of(batch()));
        when(settlementItemMapper.selectPendingItems(any())).thenReturn(List.of(item));
        when(settlementJoinMapper.selectTargetByItemId(any())).thenReturn(Optional.of(target));
        when(exchangeRateProvider.getFinalRate("USD", target.getFinalAt().toLocalDate()))
                .thenReturn(new BigDecimal("1400"));

        tasklet.execute(contribution, new ChunkContext(new StepContext(stepExecution)));

        verify(exchangeRateProvider).getFinalRate("USD", target.getFinalAt().toLocalDate());
        verify(settlementTransactionExecutor).execute(target, new BigDecimal("1400"));
        assertThat(stepExecution.getExecutionContext().containsKey(cacheKey + ".failureType"))
                .isFalse();
        assertThat(stepExecution.getExecutionContext().containsKey(cacheKey + ".failureMessage"))
                .isFalse();
    }

    @Test
    void removesUnknownFailureTypeAndFetchesRateAgain() {
        String cacheKey = "settlement.rate.USD:2026-08-04";
        stepExecution.getExecutionContext().putString(cacheKey + ".failureType", "UNKNOWN");
        stepExecution.getExecutionContext().putString(cacheKey + ".failureMessage", "손상된 실패 타입");
        SettlementItemDTO item = SettlementItemDTO.builder().itemId(10L).batchId(1L).build();
        SettlementJoinDTO target = target(10L);
        when(settlementBatchMapper.selectBatchById(1L)).thenReturn(Optional.of(batch()));
        when(settlementItemMapper.selectPendingItems(any())).thenReturn(List.of(item));
        when(settlementJoinMapper.selectTargetByItemId(any())).thenReturn(Optional.of(target));
        when(exchangeRateProvider.getFinalRate("USD", target.getFinalAt().toLocalDate()))
                .thenReturn(new BigDecimal("1400"));

        tasklet.execute(contribution, new ChunkContext(new StepContext(stepExecution)));

        verify(exchangeRateProvider).getFinalRate("USD", target.getFinalAt().toLocalDate());
        verify(settlementTransactionExecutor).execute(target, new BigDecimal("1400"));
        assertThat(stepExecution.getExecutionContext().containsKey(cacheKey + ".failureType"))
                .isFalse();
        assertThat(stepExecution.getExecutionContext().containsKey(cacheKey + ".failureMessage"))
                .isFalse();
    }

    @Test
    void cachesRateForSameCurrencyAndDateWithinPage() {
        SettlementBatchDTO batch = batch();
        SettlementItemDTO first = SettlementItemDTO.builder().itemId(10L).batchId(1L).build();
        SettlementItemDTO second = SettlementItemDTO.builder().itemId(11L).batchId(1L).build();
        SettlementJoinDTO firstTarget = target(10L);
        SettlementJoinDTO secondTarget = target(11L);
        when(settlementBatchMapper.selectBatchById(1L)).thenReturn(Optional.of(batch));
        when(settlementItemMapper.selectPendingItems(any())).thenReturn(List.of(first, second));
        when(settlementJoinMapper.selectTargetByItemId(any()))
                .thenReturn(Optional.of(firstTarget), Optional.of(secondTarget));
        when(exchangeRateProvider.getFinalRate("USD", batch.getExecutedAt().toLocalDate()))
                .thenReturn(new BigDecimal("1400"));

        tasklet.execute(contribution, new ChunkContext(new StepContext(stepExecution)));

        verify(exchangeRateProvider, times(1))
                .getFinalRate("USD", batch.getExecutedAt().toLocalDate());
        verify(settlementTransactionExecutor).execute(firstTarget, new BigDecimal("1400"));
        verify(settlementTransactionExecutor).execute(secondTarget, new BigDecimal("1400"));
    }

    @Test
    void reusesCachedRateOnNextTaskletPage() {
        SettlementBatchDTO batch = batch();
        SettlementItemDTO first = SettlementItemDTO.builder().itemId(10L).batchId(1L).build();
        SettlementItemDTO second = SettlementItemDTO.builder().itemId(11L).batchId(1L).build();
        SettlementJoinDTO firstTarget = target(10L);
        SettlementJoinDTO secondTarget = target(11L);
        when(settlementBatchMapper.selectBatchById(1L)).thenReturn(Optional.of(batch));
        when(settlementItemMapper.selectPendingItems(any()))
                .thenReturn(List.of(first), List.of(second));
        when(settlementJoinMapper.selectTargetByItemId(any()))
                .thenReturn(Optional.of(firstTarget), Optional.of(secondTarget));
        when(exchangeRateProvider.getFinalRate("USD", batch.getExecutedAt().toLocalDate()))
                .thenReturn(new BigDecimal("1400"));
        ChunkContext chunkContext = new ChunkContext(new StepContext(stepExecution));

        tasklet.execute(contribution, chunkContext);
        tasklet.execute(contribution, chunkContext);

        verify(exchangeRateProvider, times(1))
                .getFinalRate("USD", batch.getExecutedAt().toLocalDate());
        verify(settlementTransactionExecutor).execute(firstTarget, new BigDecimal("1400"));
        verify(settlementTransactionExecutor).execute(secondTarget, new BigDecimal("1400"));
    }

    @Test
    void queriesRateOnceForEachCurrency() {
        SettlementBatchDTO batch = batch();
        SettlementItemDTO first = SettlementItemDTO.builder().itemId(10L).batchId(1L).build();
        SettlementItemDTO second = SettlementItemDTO.builder().itemId(11L).batchId(1L).build();
        SettlementJoinDTO usdTarget = target(10L, "USD");
        SettlementJoinDTO jpyTarget = target(11L, "JPY");
        when(settlementBatchMapper.selectBatchById(1L)).thenReturn(Optional.of(batch));
        when(settlementItemMapper.selectPendingItems(any())).thenReturn(List.of(first, second));
        when(settlementJoinMapper.selectTargetByItemId(any()))
                .thenReturn(Optional.of(usdTarget), Optional.of(jpyTarget));
        when(exchangeRateProvider.getFinalRate("USD", batch.getExecutedAt().toLocalDate()))
                .thenReturn(new BigDecimal("1400"));
        when(exchangeRateProvider.getFinalRate("JPY", batch.getExecutedAt().toLocalDate()))
                .thenReturn(new BigDecimal("9.5"));

        tasklet.execute(contribution, new ChunkContext(new StepContext(stepExecution)));

        verify(exchangeRateProvider, times(1))
                .getFinalRate("USD", batch.getExecutedAt().toLocalDate());
        verify(exchangeRateProvider, times(1))
                .getFinalRate("JPY", batch.getExecutedAt().toLocalDate());
        verify(settlementTransactionExecutor).execute(usdTarget, new BigDecimal("1400"));
        verify(settlementTransactionExecutor).execute(jpyTarget, new BigDecimal("9.5"));
    }

    @Test
    void queriesSameCurrencyRateForEachSettlementDate() {
        SettlementItemDTO first = SettlementItemDTO.builder().itemId(10L).batchId(1L).build();
        SettlementItemDTO second = SettlementItemDTO.builder().itemId(11L).batchId(1L).build();
        SettlementJoinDTO firstTarget = target(10L);
        SettlementJoinDTO secondTarget = target(11L);
        secondTarget.setFinalAt(LocalDateTime.of(2026, 8, 5, 0, 0));
        when(settlementBatchMapper.selectBatchById(1L)).thenReturn(Optional.of(batch()));
        when(settlementItemMapper.selectPendingItems(any())).thenReturn(List.of(first, second));
        when(settlementJoinMapper.selectTargetByItemId(any()))
                .thenReturn(Optional.of(firstTarget), Optional.of(secondTarget));
        when(exchangeRateProvider.getFinalRate("USD", firstTarget.getFinalAt().toLocalDate()))
                .thenReturn(new BigDecimal("1400"));
        when(exchangeRateProvider.getFinalRate("USD", secondTarget.getFinalAt().toLocalDate()))
                .thenReturn(new BigDecimal("1410"));

        tasklet.execute(contribution, new ChunkContext(new StepContext(stepExecution)));

        verify(exchangeRateProvider).getFinalRate("USD", firstTarget.getFinalAt().toLocalDate());
        verify(exchangeRateProvider).getFinalRate("USD", secondTarget.getFinalAt().toLocalDate());
        verify(settlementTransactionExecutor).execute(firstTarget, new BigDecimal("1400"));
        verify(settlementTransactionExecutor).execute(secondTarget, new BigDecimal("1410"));
    }

    @Test
    void recordsFailureAndContinuesWhenOneItemFails() {
        SettlementBatchDTO batch = batch();
        SettlementItemDTO item = SettlementItemDTO.builder().itemId(10L).batchId(1L).build();
        when(settlementBatchMapper.selectBatchById(1L)).thenReturn(Optional.of(batch));
        when(settlementItemMapper.selectPendingItems(any())).thenReturn(List.of(item));
        when(settlementJoinMapper.selectTargetByItemId(any())).thenReturn(Optional.of(target(10L)));
        when(exchangeRateProvider.getFinalRate(eq("USD"), any()))
                .thenThrow(new ExchangeRateNotFoundException("환율 없음"));

        RepeatStatus status =
                tasklet.execute(contribution, new ChunkContext(new StepContext(stepExecution)));

        assertThat(status).isEqualTo(RepeatStatus.CONTINUABLE);
        verify(settlementFailureRecorder).markFailed(eq(10L), any(Exception.class));
        verify(settlementTransactionExecutor, never()).execute(any(), any());
    }

    @Test
    void cachesRateFailureForSameCurrencyAndDateAcrossItems() {
        SettlementBatchDTO batch = batch();
        SettlementItemDTO first = SettlementItemDTO.builder().itemId(10L).batchId(1L).build();
        SettlementItemDTO second = SettlementItemDTO.builder().itemId(11L).batchId(1L).build();
        when(settlementBatchMapper.selectBatchById(1L)).thenReturn(Optional.of(batch));
        when(settlementItemMapper.selectPendingItems(any())).thenReturn(List.of(first, second));
        when(settlementJoinMapper.selectTargetByItemId(any()))
                .thenReturn(Optional.of(target(10L)), Optional.of(target(11L)));
        when(exchangeRateProvider.getFinalRate("USD", batch.getExecutedAt().toLocalDate()))
                .thenThrow(new ExchangeRateNotFoundException("환율 없음"));

        tasklet.execute(contribution, new ChunkContext(new StepContext(stepExecution)));

        verify(exchangeRateProvider, times(1))
                .getFinalRate("USD", batch.getExecutedAt().toLocalDate());
        verify(settlementFailureRecorder)
                .markFailed(eq(10L), any(ExchangeRateNotFoundException.class));
        verify(settlementFailureRecorder)
                .markFailed(eq(11L), any(ExchangeRateNotFoundException.class));
        verify(settlementTransactionExecutor, never()).execute(any(), any());
    }

    @Test
    void reusesCachedRateFailureOnNextTaskletPage() {
        SettlementBatchDTO batch = batch();
        SettlementItemDTO first = SettlementItemDTO.builder().itemId(10L).batchId(1L).build();
        SettlementItemDTO second = SettlementItemDTO.builder().itemId(11L).batchId(1L).build();
        when(settlementBatchMapper.selectBatchById(1L)).thenReturn(Optional.of(batch));
        when(settlementItemMapper.selectPendingItems(any()))
                .thenReturn(List.of(first), List.of(second));
        when(settlementJoinMapper.selectTargetByItemId(any()))
                .thenReturn(Optional.of(target(10L)), Optional.of(target(11L)));
        when(exchangeRateProvider.getFinalRate("USD", batch.getExecutedAt().toLocalDate()))
                .thenThrow(new ExchangeRateNotFoundException("환율 없음"));
        ChunkContext chunkContext = new ChunkContext(new StepContext(stepExecution));

        tasklet.execute(contribution, chunkContext);
        tasklet.execute(contribution, chunkContext);

        verify(exchangeRateProvider, times(1))
                .getFinalRate("USD", batch.getExecutedAt().toLocalDate());
        verify(settlementFailureRecorder)
                .markFailed(eq(10L), any(ExchangeRateNotFoundException.class));
        verify(settlementFailureRecorder)
                .markFailed(eq(11L), any(ExchangeRateNotFoundException.class));
    }

    @Test
    void reusesCachedExternalApiFailureOnNextTaskletPage() {
        SettlementBatchDTO batch = batch();
        SettlementItemDTO first = SettlementItemDTO.builder().itemId(10L).batchId(1L).build();
        SettlementItemDTO second = SettlementItemDTO.builder().itemId(11L).batchId(1L).build();
        when(settlementBatchMapper.selectBatchById(1L)).thenReturn(Optional.of(batch));
        when(settlementItemMapper.selectPendingItems(any()))
                .thenReturn(List.of(first), List.of(second));
        when(settlementJoinMapper.selectTargetByItemId(any()))
                .thenReturn(Optional.of(target(10L)), Optional.of(target(11L)));
        when(exchangeRateProvider.getFinalRate("USD", batch.getExecutedAt().toLocalDate()))
                .thenThrow(new ExchangeRateExternalApiException("환율 API 장애", null));
        ChunkContext chunkContext = new ChunkContext(new StepContext(stepExecution));

        tasklet.execute(contribution, chunkContext);
        tasklet.execute(contribution, chunkContext);

        verify(exchangeRateProvider, times(1))
                .getFinalRate("USD", batch.getExecutedAt().toLocalDate());
        verify(settlementFailureRecorder)
                .markFailed(eq(10L), any(ExchangeRateExternalApiException.class));
        verify(settlementFailureRecorder)
                .markFailed(eq(11L), any(ExchangeRateExternalApiException.class));
        verify(settlementTransactionExecutor, never()).execute(any(), any());
    }

    @Test
    void marksBatchFailedWhenCompletedPageContainsFailedItems() {
        when(settlementBatchMapper.selectBatchById(1L)).thenReturn(Optional.of(batch()));
        when(settlementItemMapper.selectPendingItems(any())).thenReturn(List.of());
        when(settlementItemMapper.countPendingItems(1L)).thenReturn(0);

        tasklet.execute(contribution, new ChunkContext(new StepContext(stepExecution)));

        verify(settlementBatchStatusUpdater).completeFromLatestItems(1L);
    }

    @Test
    void doesNotCompleteBatchWhenPendingItemsRemain() {
        when(settlementBatchMapper.selectBatchById(1L)).thenReturn(Optional.of(batch()));
        when(settlementItemMapper.selectPendingItems(any())).thenReturn(List.of());
        when(settlementItemMapper.countPendingItems(1L)).thenReturn(1);

        assertThatThrownBy(
                        () ->
                                tasklet.execute(
                                        contribution,
                                        new ChunkContext(new StepContext(stepExecution))))
                .isInstanceOf(SettlementStateConflictException.class);

        verify(settlementBatchStatusUpdater, never()).completeFromLatestItems(any());
    }

    @Test
    void rejectsJobWhenRunIdDoesNotMatchCurrentBatchRunId() {
        SettlementBatchDTO batch = batch();
        batch.setRunId("new-run-id");
        when(settlementBatchMapper.selectBatchById(1L)).thenReturn(Optional.of(batch));

        assertThatThrownBy(
                        () ->
                                tasklet.execute(
                                        contribution,
                                        new ChunkContext(new StepContext(stepExecution))))
                .isInstanceOf(SettlementStateConflictException.class);
    }

    private SettlementBatchDTO batch() {
        return SettlementBatchDTO.builder()
                .batchId(1L)
                .runId("run-1")
                .status(BatchStatus.RUNNING)
                .executedAt(LocalDateTime.of(2026, 8, 4, 9, 0))
                .build();
    }

    private SettlementJoinDTO target(Long itemId) {
        return target(itemId, "USD");
    }

    private SettlementJoinDTO target(Long itemId, String currency) {
        return SettlementJoinDTO.builder()
                .itemId(itemId)
                .batchId(1L)
                .purchaseCurrency(currency)
                .finalAt(LocalDateTime.of(2026, 8, 4, 0, 0))
                .build();
    }
}
