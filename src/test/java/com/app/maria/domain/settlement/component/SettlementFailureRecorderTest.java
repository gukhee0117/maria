package com.app.maria.domain.settlement.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.app.maria.domain.settlement.dto.SettlementItemDTO;
import com.app.maria.domain.settlement.mapper.SettlementItemMapper;
import com.app.maria.domain.settlement.type.SettlementFailureCode;
import com.app.maria.domain.settlement.type.SettlementItemResult;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SettlementFailureRecorderTest {

    @Mock private SettlementItemMapper settlementItemMapper;
    @Mock private BusinessClockService clockService;

    @Test
    void recordsFailedItemInIndependentComponent() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 7, 16, 0);
        when(clockService.now()).thenReturn(now);
        when(settlementItemMapper.updateItemResult(any(SettlementItemDTO.class))).thenReturn(1);
        SettlementFailureRecorder recorder =
                new SettlementFailureRecorder(settlementItemMapper, clockService);

        recorder.markFailed(10L, new IllegalStateException("실패"));

        ArgumentCaptor<SettlementItemDTO> captor = ArgumentCaptor.forClass(SettlementItemDTO.class);
        verify(settlementItemMapper).updateItemResult(captor.capture());
        assertThat(captor.getValue().getItemId()).isEqualTo(10L);
        assertThat(captor.getValue().getResult()).isEqualTo(SettlementItemResult.FAILED);
        assertThat(captor.getValue().getProcessedAt()).isEqualTo(now);
        assertThat(captor.getValue().getFailureMessage()).isEqualTo("실패");
    }

    @Test
    void rejectsWhenFailureUpdateDoesNotAffectOneRow() {
        when(settlementItemMapper.updateItemResult(any(SettlementItemDTO.class))).thenReturn(0);
        SettlementFailureRecorder recorder =
                new SettlementFailureRecorder(settlementItemMapper, clockService);

        assertThatThrownBy(() -> recorder.markFailed(10L, new IllegalStateException("실패")))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.SETTLEMENT_STATE_CONFLICT);
    }

    @Test
    void classifiesCalculationFailureSeparately() {
        when(settlementItemMapper.updateItemResult(any(SettlementItemDTO.class))).thenReturn(1);
        SettlementFailureRecorder recorder =
                new SettlementFailureRecorder(settlementItemMapper, clockService);

        recorder.markFailed(
                10L, new AppException(ErrorType.SETTLEMENT_CALCULATION_FAILED, "계산 실패"));

        ArgumentCaptor<SettlementItemDTO> captor = ArgumentCaptor.forClass(SettlementItemDTO.class);
        verify(settlementItemMapper).updateItemResult(captor.capture());
        assertThat(captor.getValue().getFailureCode())
                .isEqualTo(SettlementFailureCode.CALCULATION_ERROR);
    }

    @ParameterizedTest
    @CsvSource({
        "EXCHANGE_RATE_NOT_FOUND, EXCHANGE_RATE_NOT_FOUND",
        "EXCHANGE_RATE_API_FAILED, EXTERNAL_API_ERROR",
        "KRW_EXCHANGE_NOT_FOUND, EXCHANGE_NOT_FOUND",
        "ACCOUNT_NOT_FOUND, ACCOUNT_NOT_FOUND",
        "SETTLEMENT_ACCOUNT_MISMATCH, ACCOUNT_MISMATCH",
        "SETTLEMENT_INVALID_REQUEST, INVALID_ORDER_STATUS",
        "SETTLEMENT_CALCULATION_FAILED, CALCULATION_ERROR",
        "SETTLEMENT_STATE_CONFLICT, DB_STATE_CONFLICT",
        "SETTLEMENT_BATCH_LOCK_FAILED, UNKNOWN_ERROR"
    })
    void classifiesFailureCodeByErrorType(ErrorType errorType, SettlementFailureCode expected) {
        when(settlementItemMapper.updateItemResult(any(SettlementItemDTO.class))).thenReturn(1);
        SettlementFailureRecorder recorder =
                new SettlementFailureRecorder(settlementItemMapper, clockService);

        recorder.markFailed(10L, new AppException(errorType, "detail"));

        assertRecordedFailureCode(expected);
    }

    @Test
    void rejectsNullItemIdWithoutMapperCall() {
        SettlementFailureRecorder recorder =
                new SettlementFailureRecorder(settlementItemMapper, clockService);

        assertThatThrownBy(() -> recorder.markFailed(null, new IllegalStateException("실패")))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.SETTLEMENT_STATE_CONFLICT);

        verify(settlementItemMapper, never()).updateItemResult(any());
    }

    private void assertRecordedFailureCode(SettlementFailureCode expectedFailureCode) {
        ArgumentCaptor<SettlementItemDTO> captor = ArgumentCaptor.forClass(SettlementItemDTO.class);
        verify(settlementItemMapper).updateItemResult(captor.capture());
        assertThat(captor.getValue().getFailureCode()).isEqualTo(expectedFailureCode);
    }
}
