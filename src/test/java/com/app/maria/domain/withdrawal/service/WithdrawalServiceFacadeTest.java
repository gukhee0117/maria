package com.app.maria.domain.withdrawal.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.withdrawal.dto.WithdrawalFailureContext;
import com.app.maria.domain.withdrawal.dto.request.WithdrawalRequestDTO;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WithdrawalServiceFacadeTest {

    @Mock private WithdrawalProcessor withdrawalProcessor;
    @Mock private WithdrawalFailureService withdrawalFailureService;

    @InjectMocks private WithdrawalServiceFacade withdrawalServiceFacade;

    @Test
    void insufficientBalance_isRecordedAndOriginalExceptionIsRethrown() {
        WithdrawalRequestDTO request = request();
        WithdrawalFailureContext context = failureContext();
        AppException exception =
                new AppException(ErrorType.INSUFFICIENT_WITHDRAWAL_AMOUNT, context);
        when(withdrawalProcessor.withdraw(request)).thenThrow(exception);

        assertThatThrownBy(() -> withdrawalServiceFacade.withdraw(request)).isSameAs(exception);

        verify(withdrawalFailureService).recordInsufficientBalance(context);
    }

    @Test
    void nonRecordableFailure_doesNotCreateFailedWithdrawal() {
        WithdrawalRequestDTO request = request();
        AppException exception = new AppException(ErrorType.ACCOUNT_STATUS_NOT_WITHDRAWABLE);
        when(withdrawalProcessor.withdraw(request)).thenThrow(exception);

        assertThatThrownBy(() -> withdrawalServiceFacade.withdraw(request)).isSameAs(exception);

        verify(withdrawalFailureService, never())
                .recordInsufficientBalance(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void closureInsufficientBalance_isAlsoRecorded() {
        WithdrawalRequestDTO request = request();
        WithdrawalFailureContext context = failureContext();
        AppException exception =
                new AppException(ErrorType.INSUFFICIENT_WITHDRAWAL_AMOUNT, context);
        when(withdrawalProcessor.withdrawForClosure(request)).thenThrow(exception);

        assertThatThrownBy(() -> withdrawalServiceFacade.withdrawForClosure(request))
                .isSameAs(exception);

        verify(withdrawalFailureService).recordInsufficientBalance(context);
    }

    private WithdrawalRequestDTO request() {
        return WithdrawalRequestDTO.builder()
                .accountId(10L)
                .requestedAmount(new BigDecimal("700000"))
                .destinationGeneralAccountId(20L)
                .build();
    }

    private WithdrawalFailureContext failureContext() {
        return new WithdrawalFailureContext(
                10L,
                new BigDecimal("700000"),
                LocalDateTime.of(2026, 8, 18, 10, 30),
                "1234567890",
                20L);
    }
}
