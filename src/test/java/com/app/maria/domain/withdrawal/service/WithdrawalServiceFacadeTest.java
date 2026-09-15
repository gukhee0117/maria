package com.app.maria.domain.withdrawal.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.withdrawal.dto.request.WithdrawalRequestDTO;
import com.app.maria.domain.withdrawal.exception.InsufficientWithdrawalAmountException;
import com.app.maria.domain.withdrawal.exception.WithdrawalNotAllowedException;
import java.math.BigDecimal;
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
        InsufficientWithdrawalAmountException exception =
                new InsufficientWithdrawalAmountException("계좌 잔액이 부족합니다.");
        when(withdrawalProcessor.withdraw(request)).thenThrow(exception);

        assertThatThrownBy(() -> withdrawalServiceFacade.withdraw(request)).isSameAs(exception);

        verify(withdrawalFailureService).recordInsufficientBalance(exception);
    }

    @Test
    void nonRecordableFailure_doesNotCreateFailedWithdrawal() {
        WithdrawalRequestDTO request = request();
        WithdrawalNotAllowedException exception =
                new WithdrawalNotAllowedException("인출할 수 없는 계좌 상태입니다.");
        when(withdrawalProcessor.withdraw(request)).thenThrow(exception);

        assertThatThrownBy(() -> withdrawalServiceFacade.withdraw(request)).isSameAs(exception);

        verify(withdrawalFailureService, never())
                .recordInsufficientBalance(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void closureInsufficientBalance_isAlsoRecorded() {
        WithdrawalRequestDTO request = request();
        InsufficientWithdrawalAmountException exception =
                new InsufficientWithdrawalAmountException("계좌 잔액이 부족합니다.");
        when(withdrawalProcessor.withdrawForClosure(request)).thenThrow(exception);

        assertThatThrownBy(() -> withdrawalServiceFacade.withdrawForClosure(request))
                .isSameAs(exception);

        verify(withdrawalFailureService).recordInsufficientBalance(exception);
    }

    private WithdrawalRequestDTO request() {
        return WithdrawalRequestDTO.builder()
                .accountId(10L)
                .requestedAmount(new BigDecimal("700000"))
                .destinationGeneralAccountId(20L)
                .build();
    }
}
