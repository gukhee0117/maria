package com.app.maria.domain.accountclosure.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.accountclosure.dto.AccountClosureDTO;
import com.app.maria.domain.accountclosure.mapper.AccountClosureMapper;
import com.app.maria.domain.accountclosure.type.AccountClosureStatus;
import com.app.maria.domain.withdrawal.dto.WithdrawalFailureContext;
import com.app.maria.domain.withdrawal.service.WithdrawalFailureService;
import com.app.maria.global.client.generalaccount.GeneralAccountClient;
import com.app.maria.global.client.generalaccount.dto.response.GeneralAccountResponseDTO;
import com.app.maria.global.client.generalaccount.type.GeneralAccountStatus;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
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
    private static final Long CUSTOMER_ID = 30L;
    private static final Long GENERAL_ACCOUNT_ID = 40L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 23, 10, 0);

    @Mock AccountClosureService accountClosureService;
    @Mock AccountClosureMapper accountClosureMapper;
    @Mock AccountMapper accountMapper;
    @Mock WithdrawalFailureService withdrawalFailureService;
    @Mock BusinessClockService businessClockService;
    @Mock GeneralAccountClient generalAccountClient;
    @InjectMocks AccountClosureApprovalOrchestrator orchestrator;

    @Test
    void successfulApproval_doesNotRecordFailure() {
        // 성공 경로에서는 FAILED 행 저장이 없어야 한다
        orchestrator.approveClosure(ADMIN_ID, CLOSURE_REQUEST_ID);

        verify(accountClosureService).approveClosure(ADMIN_ID, CLOSURE_REQUEST_ID);
        verify(withdrawalFailureService, never()).record(any());
    }

    @Test
    void approvalFailure_withPositiveBalance_recordsFailedWithdrawal() {
        prepareClosureContext(new BigDecimal("500000"));
        AppException exception = new AppException(ErrorType.ACCOUNT_CLOSURE_PROCESSING_FAILED);
        doThrow(exception).when(accountClosureService).approveClosure(ADMIN_ID, CLOSURE_REQUEST_ID);
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
    void approvalFailure_withZeroBalance_doesNotRecordFailedWithdrawal() {
        // 잔액이 0이면 인출 시도 자체가 없으므로 FAILED 행이 필요 없다
        prepareClosureContext(BigDecimal.ZERO);
        AppException exception = new AppException(ErrorType.ACCOUNT_CLOSURE_PROCESSING_FAILED);
        doThrow(exception).when(accountClosureService).approveClosure(ADMIN_ID, CLOSURE_REQUEST_ID);

        assertThatThrownBy(() -> orchestrator.approveClosure(ADMIN_ID, CLOSURE_REQUEST_ID))
                .isSameAs(exception);

        verify(withdrawalFailureService, never()).record(any());
    }

    @Test
    void approvalFailure_whenPrefetchFails_originalExceptionIsPropagated() {
        // 컨텍스트 조회 실패 시 원본 예외가 그대로 전파되어야 한다
        when(accountClosureMapper.selectById(CLOSURE_REQUEST_ID)).thenReturn(Optional.empty());
        AppException exception = new AppException(ErrorType.ACCOUNT_CLOSURE_PROCESSING_FAILED);
        doThrow(exception).when(accountClosureService).approveClosure(ADMIN_ID, CLOSURE_REQUEST_ID);

        assertThatThrownBy(() -> orchestrator.approveClosure(ADMIN_ID, CLOSURE_REQUEST_ID))
                .isSameAs(exception);

        verify(withdrawalFailureService, never()).record(any());
    }

    @Test
    void approvalFailure_whenFailureRecordingThrows_originalExceptionIsStillPropagated() {
        prepareClosureContext(new BigDecimal("500000"));
        AppException originalException =
                new AppException(ErrorType.ACCOUNT_CLOSURE_PROCESSING_FAILED);
        doThrow(originalException)
                .when(accountClosureService)
                .approveClosure(ADMIN_ID, CLOSURE_REQUEST_ID);
        when(businessClockService.now()).thenReturn(NOW);
        doThrow(new RuntimeException("DB 장애"))
                .when(withdrawalFailureService)
                .record(any());

        assertThatThrownBy(() -> orchestrator.approveClosure(ADMIN_ID, CLOSURE_REQUEST_ID))
                .isSameAs(originalException);
    }

    private void prepareClosureContext(BigDecimal accountAmount) {
        AccountClosureDTO closure =
                AccountClosureDTO.builder()
                        .closureRequestId(CLOSURE_REQUEST_ID)
                        .accountId(ACCOUNT_ID)
                        .destinationGeneralAccountId(GENERAL_ACCOUNT_ID)
                        .status(AccountClosureStatus.REQUESTED)
                        .build();
        AccountDTO account =
                AccountDTO.builder()
                        .accountId(ACCOUNT_ID)
                        .customerId(CUSTOMER_ID)
                        .amount(accountAmount)
                        .build();

        when(accountClosureMapper.selectById(CLOSURE_REQUEST_ID)).thenReturn(Optional.of(closure));
        when(accountMapper.selectByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(account));

        if (accountAmount.compareTo(BigDecimal.ZERO) > 0) {
            when(accountMapper.selectCiHashByCustomerId(CUSTOMER_ID))
                    .thenReturn(Optional.of("ci-hash"));
            when(generalAccountClient.verifyGeneralAccount(any()))
                    .thenReturn(
                            GeneralAccountResponseDTO.builder()
                                    .generalAccountId(GENERAL_ACCOUNT_ID)
                                    .accountNo("110-123-456789")
                                    .status(GeneralAccountStatus.ACTIVE)
                                    .build());
        }
    }
}
