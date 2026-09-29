package com.app.maria.domain.withdrawal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.withdrawal.dto.WithdrawalDTO;
import com.app.maria.domain.withdrawal.dto.WithdrawalFailureContext;
import com.app.maria.domain.withdrawal.mapper.WithdrawalMapper;
import com.app.maria.domain.withdrawal.type.WithdrawalStatus;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WithdrawalFailureServiceTest {

    private static final LocalDateTime FAILED_AT = LocalDateTime.of(2026, 8, 18, 10, 30);

    @Mock private WithdrawalMapper withdrawalMapper;

    @InjectMocks private WithdrawalFailureService withdrawalFailureService;

    @Test
    void insufficientBalance_isStoredAsFailedWithdrawal() {
        WithdrawalFailureContext context = failureContext();
        assertThat(context.toString()).doesNotContain("1234567890");
        when(withdrawalMapper.insertWithdrawal(org.mockito.ArgumentMatchers.any())).thenReturn(1);

        withdrawalFailureService.recordInsufficientBalance(context);

        ArgumentCaptor<WithdrawalDTO> captor = ArgumentCaptor.forClass(WithdrawalDTO.class);
        verify(withdrawalMapper).insertWithdrawal(captor.capture());
        WithdrawalDTO failedWithdrawal = captor.getValue();
        assertThat(failedWithdrawal.getAccountId()).isEqualTo(10L);
        assertThat(failedWithdrawal.getRequestedAmount()).isEqualByComparingTo("700000");
        assertThat(failedWithdrawal.getProcessedAt()).isEqualTo(FAILED_AT);
        assertThat(failedWithdrawal.getDestinationAccountNo()).isEqualTo("1234567890");
        assertThat(failedWithdrawal.getDestinationGeneralAccountId()).isEqualTo(20L);
        assertThat(failedWithdrawal.getStatus()).isEqualTo(WithdrawalStatus.FAILED);
    }

    @Test
    void failedWithdrawalInsert_isRejected() {
        WithdrawalFailureContext context = failureContext();
        when(withdrawalMapper.insertWithdrawal(org.mockito.ArgumentMatchers.any())).thenReturn(0);

        assertThatThrownBy(() -> withdrawalFailureService.recordInsufficientBalance(context))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.WITHDRAWAL_PROCESSING_FAILED.getMessage());
    }

    private WithdrawalFailureContext failureContext() {
        return new WithdrawalFailureContext(
                10L, new BigDecimal("700000"), FAILED_AT, "1234567890", 20L);
    }
}
