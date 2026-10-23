package com.app.maria.domain.accountclosure.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.withdrawal.dto.WithdrawalFailureContext;
import com.app.maria.domain.withdrawal.service.ClosureWithdrawalContextHolder;
import com.app.maria.domain.withdrawal.service.WithdrawalFailureService;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountClosureApprovalOrchestratorTest {

    private static final Long ADMIN_ID = 1L;
    private static final Long CLOSURE_REQUEST_ID = 10L;
    private static final Long ACCOUNT_ID = 20L;
    private static final Long GENERAL_ACCOUNT_ID = 40L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 23, 10, 0);

    @Mock AccountClosureService accountClosureService;
    @Mock ClosureWithdrawalContextHolder closureWithdrawalContextHolder;
    @Mock WithdrawalFailureService withdrawalFailureService;
    @Mock BusinessClockService businessClockService;
    @InjectMocks AccountClosureApprovalOrchestrator orchestrator;

    @Test
    void successfulApproval_doesNotRecordFailure() {
        orchestrator.approveClosure(ADMIN_ID, CLOSURE_REQUEST_ID);

        verify(accountClosureService).approveClosure(ADMIN_ID, CLOSURE_REQUEST_ID);
        verify(withdrawalFailureService, never()).record(any());
        // 시작 시 pre-emptive clear + finally clear = 총 2회
        verify(closureWithdrawalContextHolder, times(2)).clear();
    }

    @Test
    void approvalFailure_withContextSet_recordsFailedWithdrawal() {
        AppException exception = new AppException(ErrorType.ACCOUNT_CLOSURE_PROCESSING_FAILED);
        doThrow(exception).when(accountClosureService).approveClosure(ADMIN_ID, CLOSURE_REQUEST_ID);
        when(closureWithdrawalContextHolder.getAndClear())
                .thenReturn(
                        new ClosureWithdrawalContextHolder.Attempt(
                                ACCOUNT_ID,
                                new BigDecimal("500000"),
                                "110-123-456789",
                                GENERAL_ACCOUNT_ID));
        when(businessClockService.now()).thenReturn(NOW);

        assertThatThrownBy(() -> orchestrator.approveClosure(ADMIN_ID, CLOSURE_REQUEST_ID))
                .isSameAs(exception);

        ArgumentCaptor<WithdrawalFailureContext> captor =
                ArgumentCaptor.forClass(WithdrawalFailureContext.class);
        verify(withdrawalFailureService).record(captor.capture());
        WithdrawalFailureContext ctx = captor.getValue();
        assertThat(ctx.getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(ctx.getRequestedAmount()).isEqualByComparingTo("500000");
        assertThat(ctx.getFailedAt()).isEqualTo(NOW);
        assertThat(ctx.getDestinationAccountNo()).isEqualTo("110-123-456789");
        assertThat(ctx.getDestinationGeneralAccountId()).isEqualTo(GENERAL_ACCOUNT_ID);
    }

    @Test
    void approvalFailure_withContextNull_doesNotRecordFailedWithdrawal() {
        // 반려·상태 불일치 등으로 강제인출이 시도되지 않으면 컨텍스트가 null이다
        AppException exception = new AppException(ErrorType.ACCOUNT_CLOSURE_PROCESSING_FAILED);
        doThrow(exception).when(accountClosureService).approveClosure(ADMIN_ID, CLOSURE_REQUEST_ID);
        when(closureWithdrawalContextHolder.getAndClear()).thenReturn(null);

        assertThatThrownBy(() -> orchestrator.approveClosure(ADMIN_ID, CLOSURE_REQUEST_ID))
                .isSameAs(exception);

        verify(withdrawalFailureService, never()).record(any());
    }

    @Test
    void approvalFailure_failedAmountEqualsActualRequestedAmount() {
        // FAILED 행의 금액은 사전조회 금액이 아니라 ThreadLocal에 담긴 실제 인출 요청액이어야 한다.
        // 사전조회 후 정산이 완료되어 잔액이 바뀌더라도 FAILED 금액은 실제 요청액과 일치한다.
        BigDecimal actualRequestedAmount = new BigDecimal("300000");
        AppException exception = new AppException(ErrorType.ACCOUNT_CLOSURE_PROCESSING_FAILED);
        doThrow(exception).when(accountClosureService).approveClosure(ADMIN_ID, CLOSURE_REQUEST_ID);
        when(closureWithdrawalContextHolder.getAndClear())
                .thenReturn(
                        new ClosureWithdrawalContextHolder.Attempt(
                                ACCOUNT_ID,
                                actualRequestedAmount,
                                "110-123-456789",
                                GENERAL_ACCOUNT_ID));
        when(businessClockService.now()).thenReturn(NOW);

        assertThatThrownBy(() -> orchestrator.approveClosure(ADMIN_ID, CLOSURE_REQUEST_ID))
                .isSameAs(exception);

        ArgumentCaptor<WithdrawalFailureContext> captor =
                ArgumentCaptor.forClass(WithdrawalFailureContext.class);
        verify(withdrawalFailureService).record(captor.capture());
        assertThat(captor.getValue().getRequestedAmount())
                .isEqualByComparingTo(actualRequestedAmount);
    }

    @Test
    void approvalFailure_whenFailureRecordingThrows_originalExceptionIsStillPropagated() {
        AppException originalException =
                new AppException(ErrorType.ACCOUNT_CLOSURE_PROCESSING_FAILED);
        doThrow(originalException)
                .when(accountClosureService)
                .approveClosure(ADMIN_ID, CLOSURE_REQUEST_ID);
        when(closureWithdrawalContextHolder.getAndClear())
                .thenReturn(
                        new ClosureWithdrawalContextHolder.Attempt(
                                ACCOUNT_ID,
                                new BigDecimal("500000"),
                                "110-123-456789",
                                GENERAL_ACCOUNT_ID));
        when(businessClockService.now()).thenReturn(NOW);
        doThrow(new RuntimeException("DB 장애")).when(withdrawalFailureService).record(any());

        assertThatThrownBy(() -> orchestrator.approveClosure(ADMIN_ID, CLOSURE_REQUEST_ID))
                .isSameAs(originalException);
    }
}
