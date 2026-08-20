package com.app.maria.domain.accountclosure.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.exception.AccountNotFoundException;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.account.service.AccountLogService;
import com.app.maria.domain.account.type.Status;
import com.app.maria.domain.accountclosure.dto.AccountClosureDTO;
import com.app.maria.domain.accountclosure.dto.request.AccountClosureApplyRequestDTO;
import com.app.maria.domain.accountclosure.dto.response.AccountClosureDetailResponseDTO;
import com.app.maria.domain.accountclosure.dto.response.AccountClosureResponseDTO;
import com.app.maria.domain.accountclosure.exception.AccountClosureNotAllowedException;
import com.app.maria.domain.accountclosure.exception.AccountClosureNotFoundException;
import com.app.maria.domain.accountclosure.exception.AccountClosureProcessingException;
import com.app.maria.domain.accountclosure.exception.AccountClosureStateConflictException;
import com.app.maria.domain.accountclosure.mapper.AccountClosureMapper;
import com.app.maria.domain.accountclosure.type.AccountClosureStatus;
import com.app.maria.domain.withdrawal.dto.WithdrawalResultDTO;
import com.app.maria.domain.withdrawal.dto.request.WithdrawalRequestDTO;
import com.app.maria.domain.withdrawal.exception.EarlyWithdrawalConsentRequiredException;
import com.app.maria.domain.withdrawal.service.WithdrawalService;
import com.app.maria.global.audit.dto.AuditLogDTO;
import com.app.maria.global.audit.exception.AuditLogInsertException;
import com.app.maria.global.audit.provider.AuditActorProvider;
import com.app.maria.global.audit.service.AuditLogService;
import com.app.maria.global.client.generalaccount.GeneralAccountClient;
import com.app.maria.global.client.generalaccount.dto.request.GeneralAccountRequestDTO;
import com.app.maria.global.client.generalaccount.dto.response.GeneralAccountResponseDTO;
import com.app.maria.global.client.generalaccount.type.GeneralAccountStatus;
import com.app.maria.global.clock.service.BusinessClockService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountClosureServiceImplTest {

    private static final Long CUSTOMER_ID = 10L;
    private static final Long ACCOUNT_ID = 1L;
    private static final Long GENERAL_ACCOUNT_ID = 20L;
    private static final Long CLOSURE_REQUEST_ID = 30L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 12, 10, 0);

    @Mock private AccountMapper accountMapper;
    @Mock private AccountLogService accountLogService;
    @Mock private AccountClosureMapper accountClosureMapper;
    @Mock private BusinessClockService businessClockService;
    @Mock private GeneralAccountClient generalAccountClient;
    @Mock private WithdrawalService withdrawalService;
    @Mock private AuditLogService auditLogService;
    @Mock private AuditActorProvider auditActorProvider;
    @InjectMocks private AccountClosureServiceImpl accountClosureService;

    @Test
    void missingAccountStopsBeforeExternalValidation() {
        when(accountMapper.selectByCustomerId(CUSTOMER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountClosureService.applyClosure(CUSTOMER_ID, request(true)))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("해지할 계좌가 존재하지 않습니다.");

        verifyNoInteractions(generalAccountClient, accountClosureMapper, businessClockService);
        verify(accountMapper, never()).requestClosure(any());
    }

    @Test
    void nonOpenedAccountStopsBeforeCiLookupAndExternalValidation() {
        when(accountMapper.selectByCustomerId(CUSTOMER_ID))
                .thenReturn(Optional.of(account(Status.CLOSURE_REQUESTED)));

        assertThatThrownBy(() -> accountClosureService.applyClosure(CUSTOMER_ID, request(true)))
                .isInstanceOf(AccountClosureNotAllowedException.class);

        verify(accountMapper, never()).selectCiHashByCustomerId(any());
        verifyNoInteractions(generalAccountClient, accountClosureMapper, businessClockService);
    }

    @Test
    void missingCiStopsBeforeExternalValidationAndStateChange() {
        when(accountMapper.selectByCustomerId(CUSTOMER_ID))
                .thenReturn(Optional.of(account(Status.OPENED)));
        when(accountMapper.selectCiHashByCustomerId(CUSTOMER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountClosureService.applyClosure(CUSTOMER_ID, request(true)))
                .isInstanceOf(AccountNotFoundException.class);

        verifyNoInteractions(generalAccountClient, accountClosureMapper, businessClockService);
        verify(accountMapper, never()).requestClosure(ACCOUNT_ID);
    }

    @Test
    void closedDestinationStopsBeforeAccountStateChange() {
        prepareAccountAndCi();
        when(generalAccountClient.verifyGeneralAccount(any()))
                .thenReturn(generalAccount(GeneralAccountStatus.CLOSED));

        assertThatThrownBy(() -> accountClosureService.applyClosure(CUSTOMER_ID, request(true)))
                .isInstanceOf(AccountClosureNotAllowedException.class)
                .hasMessage("활성 상태의 일반계좌만 해지 정산 계좌로 선택할 수 있습니다.");

        verify(accountMapper, never()).requestClosure(ACCOUNT_ID);
        verifyNoInteractions(accountClosureMapper, businessClockService);
    }

    @Test
    void concurrentAccountStateChangeStopsBeforeClosureInsert() {
        prepareExternalValidationSuccess();
        when(accountMapper.requestClosure(ACCOUNT_ID)).thenReturn(0);

        assertThatThrownBy(() -> accountClosureService.applyClosure(CUSTOMER_ID, request(true)))
                .isInstanceOf(AccountClosureStateConflictException.class)
                .hasMessage("계좌 상태가 변경되어 해지를 신청할 수 없습니다.");

        verifyNoInteractions(accountClosureMapper, businessClockService);
    }

    @Test
    void closureInsertFailureThrowsProcessingException() {
        prepareExternalValidationSuccess();
        when(accountMapper.requestClosure(ACCOUNT_ID)).thenReturn(1);
        when(businessClockService.now()).thenReturn(NOW);
        when(accountClosureMapper.insertClosureRequest(any())).thenReturn(0);

        assertThatThrownBy(() -> accountClosureService.applyClosure(CUSTOMER_ID, request(true)))
                .isInstanceOf(AccountClosureProcessingException.class)
                .hasMessage("계좌 해지 신청 저장에 실패했습니다.");
    }

    @Test
    void successfulApplicationStoresVerifiedDestinationAndReturnsGeneratedId() {
        prepareExternalValidationSuccess();
        when(accountMapper.requestClosure(ACCOUNT_ID)).thenReturn(1);
        when(businessClockService.now()).thenReturn(NOW);
        when(auditActorProvider.getCurrentAdminId()).thenReturn(7L);
        doAnswer(
                        invocation -> {
                            AccountClosureDTO closure = invocation.getArgument(0);
                            closure.setClosureRequestId(CLOSURE_REQUEST_ID);
                            return 1;
                        })
                .when(accountClosureMapper)
                .insertClosureRequest(any(AccountClosureDTO.class));

        Long result = accountClosureService.applyClosure(CUSTOMER_ID, request(true));

        ArgumentCaptor<GeneralAccountRequestDTO> externalRequestCaptor =
                ArgumentCaptor.forClass(GeneralAccountRequestDTO.class);
        ArgumentCaptor<AccountClosureDTO> closureCaptor =
                ArgumentCaptor.forClass(AccountClosureDTO.class);
        verify(generalAccountClient).verifyGeneralAccount(externalRequestCaptor.capture());
        verify(accountClosureMapper).insertClosureRequest(closureCaptor.capture());

        assertThat(externalRequestCaptor.getValue().getCiHash()).isEqualTo("customer-ci-hash");
        assertThat(externalRequestCaptor.getValue().getGeneralAccountId())
                .isEqualTo(GENERAL_ACCOUNT_ID);
        assertThat(closureCaptor.getValue())
                .satisfies(
                        closure -> {
                            assertThat(closure.getAccountId()).isEqualTo(ACCOUNT_ID);
                            assertThat(closure.getDestinationGeneralAccountId())
                                    .isEqualTo(GENERAL_ACCOUNT_ID);
                            assertThat(closure.isEarlyWithdrawalAgreed()).isTrue();
                            assertThat(closure.getStatus())
                                    .isEqualTo(AccountClosureStatus.REQUESTED);
                            assertThat(closure.getRequestedAt()).isEqualTo(NOW);
                        });
        assertThat(result).isEqualTo(CLOSURE_REQUEST_ID);
        verify(accountLogService)
                .recordStatusChange(
                        argThat(
                                account ->
                                        account.getAccountId().equals(ACCOUNT_ID)
                                                && account.getStatus() == Status.CLOSURE_REQUESTED),
                        eq(Status.OPENED),
                        eq(NOW),
                        eq("계좌 해지 신청"));
        assertAuditLog(7L, "OPENED", "CLOSURE_REQUESTED", "ACCOUNT_CLOSURE_REQUESTED");

        InOrder order =
                inOrder(
                        accountMapper,
                        generalAccountClient,
                        businessClockService,
                        accountClosureMapper);
        order.verify(accountMapper).selectByCustomerId(CUSTOMER_ID);
        order.verify(accountMapper).selectCiHashByCustomerId(CUSTOMER_ID);
        order.verify(generalAccountClient).verifyGeneralAccount(any());
        order.verify(accountMapper).requestClosure(ACCOUNT_ID);
        order.verify(businessClockService).now();
        order.verify(accountClosureMapper).insertClosureRequest(any());
    }

    @Test
    void immaturePrincipalWithoutConsentStopsBeforeClosureStateChange() {
        prepareExternalValidationSuccess();
        when(withdrawalService.hasImmaturePrincipal(ACCOUNT_ID)).thenReturn(true);

        assertThatThrownBy(() -> accountClosureService.applyClosure(CUSTOMER_ID, request(false)))
                .isInstanceOf(EarlyWithdrawalConsentRequiredException.class)
                .hasMessage("1년 미경과 원금이 있어 계좌 해지를 위해 조기인출 동의가 필요합니다.");

        verify(accountMapper, never()).requestClosure(ACCOUNT_ID);
        verifyNoInteractions(accountClosureMapper, businessClockService);
    }

    @Test
    void immaturePrincipalWithConsentAllowsClosureApplication() {
        prepareExternalValidationSuccess();
        when(accountMapper.requestClosure(ACCOUNT_ID)).thenReturn(1);
        when(businessClockService.now()).thenReturn(NOW);
        prepareClosureInsertSuccess();

        Long result = accountClosureService.applyClosure(CUSTOMER_ID, request(true));

        assertThat(result).isEqualTo(CLOSURE_REQUEST_ID);
        verify(withdrawalService, never()).hasImmaturePrincipal(ACCOUNT_ID);
        verify(accountMapper).requestClosure(ACCOUNT_ID);
    }

    @Test
    void noImmaturePrincipalAllowsApplicationWithoutConsent() {
        prepareExternalValidationSuccess();
        when(withdrawalService.hasImmaturePrincipal(ACCOUNT_ID)).thenReturn(false);
        when(accountMapper.requestClosure(ACCOUNT_ID)).thenReturn(1);
        when(businessClockService.now()).thenReturn(NOW);
        prepareClosureInsertSuccess();

        Long result = accountClosureService.applyClosure(CUSTOMER_ID, request(false));

        assertThat(result).isEqualTo(CLOSURE_REQUEST_ID);
        verify(withdrawalService).hasImmaturePrincipal(ACCOUNT_ID);
        verify(accountMapper).requestClosure(ACCOUNT_ID);
    }

    @Test
    void missingClosureRequestStopsBeforeRejectionUpdates() {
        when(accountClosureMapper.selectByIdForUpdate(CLOSURE_REQUEST_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                        () -> accountClosureService.rejectClosure(7L, CLOSURE_REQUEST_ID, "반려 사유"))
                .isInstanceOf(AccountClosureNotFoundException.class)
                .hasMessage("계좌 해지 신청을 찾을 수 없습니다.");

        verify(accountClosureMapper, never()).rejectClosureRequest(any());
        verifyNoInteractions(accountMapper, businessClockService);
    }

    @Test
    void alreadyProcessedClosureCannotBeRejectedAgain() {
        when(accountClosureMapper.selectByIdForUpdate(CLOSURE_REQUEST_ID))
                .thenReturn(Optional.of(closure(AccountClosureStatus.COMPLETED)));

        assertThatThrownBy(
                        () -> accountClosureService.rejectClosure(7L, CLOSURE_REQUEST_ID, "반려 사유"))
                .isInstanceOf(AccountClosureNotAllowedException.class)
                .hasMessage("이미 처리된 계좌 해지 신청입니다.");

        verify(accountClosureMapper, never()).rejectClosureRequest(any());
        verifyNoInteractions(accountMapper, businessClockService);
    }

    @Test
    void closureRejectionUpdateFailureDoesNotReopenAccount() {
        AccountClosureDTO closure = closure(AccountClosureStatus.REQUESTED);
        when(accountClosureMapper.selectByIdForUpdate(CLOSURE_REQUEST_ID))
                .thenReturn(Optional.of(closure));
        when(businessClockService.now()).thenReturn(NOW);
        when(accountClosureMapper.rejectClosureRequest(closure)).thenReturn(0);

        assertThatThrownBy(
                        () -> accountClosureService.rejectClosure(7L, CLOSURE_REQUEST_ID, "반려 사유"))
                .isInstanceOf(AccountClosureProcessingException.class)
                .hasMessage("계좌 해지 신청 반려 처리에 실패했습니다.");

        verify(accountMapper, never()).reopenAfterClosureRejection(ACCOUNT_ID);
    }

    @Test
    void accountReopenFailureRaisesProcessingException() {
        AccountClosureDTO closure = closure(AccountClosureStatus.REQUESTED);
        when(accountClosureMapper.selectByIdForUpdate(CLOSURE_REQUEST_ID))
                .thenReturn(Optional.of(closure));
        when(businessClockService.now()).thenReturn(NOW);
        when(accountClosureMapper.rejectClosureRequest(closure)).thenReturn(1);
        when(accountMapper.reopenAfterClosureRejection(ACCOUNT_ID)).thenReturn(0);

        assertThatThrownBy(
                        () -> accountClosureService.rejectClosure(7L, CLOSURE_REQUEST_ID, "반려 사유"))
                .isInstanceOf(AccountClosureProcessingException.class)
                .hasMessage("해지 반려 후 계좌 상태 복구에 실패했습니다.");
    }

    @Test
    void successfulRejectionStoresProcessorReasonAndReopensAccountInOrder() {
        AccountClosureDTO closure = closure(AccountClosureStatus.REQUESTED);
        when(accountClosureMapper.selectByIdForUpdate(CLOSURE_REQUEST_ID))
                .thenReturn(Optional.of(closure));
        when(businessClockService.now()).thenReturn(NOW);
        when(accountClosureMapper.rejectClosureRequest(closure)).thenReturn(1);
        when(accountMapper.reopenAfterClosureRejection(ACCOUNT_ID)).thenReturn(1);

        accountClosureService.rejectClosure(7L, CLOSURE_REQUEST_ID, "관리자 반려 사유");

        assertThat(closure.getProcessedAt()).isEqualTo(NOW);
        assertThat(closure.getProcessedBy()).isEqualTo(7L);
        assertThat(closure.getRejectionReason()).isEqualTo("관리자 반려 사유");
        verify(accountLogService)
                .recordStatusChange(
                        argThat(
                                account ->
                                        account.getAccountId().equals(ACCOUNT_ID)
                                                && account.getStatus() == Status.OPENED),
                        eq(Status.CLOSURE_REQUESTED),
                        eq(NOW),
                        eq("계좌 해지 신청 반려: 관리자 반려 사유"));
        assertAuditLog(7L, "CLOSURE_REQUESTED", "OPENED", "ACCOUNT_CLOSURE_REJECTED");

        InOrder order = inOrder(accountClosureMapper, businessClockService, accountMapper);
        order.verify(accountClosureMapper).selectByIdForUpdate(CLOSURE_REQUEST_ID);
        order.verify(businessClockService).now();
        order.verify(accountClosureMapper).rejectClosureRequest(closure);
        order.verify(accountMapper).reopenAfterClosureRejection(ACCOUNT_ID);
    }

    @Test
    void auditLogFailurePropagatesSoClosureRejectionTransactionCanRollBack() {
        AccountClosureDTO closure = closure(AccountClosureStatus.REQUESTED);
        when(accountClosureMapper.selectByIdForUpdate(CLOSURE_REQUEST_ID))
                .thenReturn(Optional.of(closure));
        when(businessClockService.now()).thenReturn(NOW);
        when(accountClosureMapper.rejectClosureRequest(closure)).thenReturn(1);
        when(accountMapper.reopenAfterClosureRejection(ACCOUNT_ID)).thenReturn(1);
        doThrow(new AuditLogInsertException("AUDIT_LOG 저장에 실패했습니다."))
                .when(auditLogService)
                .log(any(AuditLogDTO.class));

        assertThatThrownBy(
                        () ->
                                accountClosureService.rejectClosure(
                                        7L, CLOSURE_REQUEST_ID, "관리자 반려 사유"))
                .isInstanceOf(AuditLogInsertException.class)
                .hasMessage("AUDIT_LOG 저장에 실패했습니다.");

        verify(accountClosureMapper).rejectClosureRequest(closure);
        verify(accountMapper).reopenAfterClosureRejection(ACCOUNT_ID);
    }

    @Test
    void zeroBalanceAccountClosesWithoutForcedWithdrawal() {
        AccountClosureDTO closure = closureForApproval(true);
        when(accountClosureMapper.selectByIdForUpdate(CLOSURE_REQUEST_ID))
                .thenReturn(Optional.of(closure));
        when(accountMapper.selectByAccountIdForUpdate(ACCOUNT_ID))
                .thenReturn(Optional.of(account(Status.CLOSURE_REQUESTED, "0")));
        when(accountMapper.completeClosure(ACCOUNT_ID)).thenReturn(1);
        when(businessClockService.now()).thenReturn(NOW);
        when(accountClosureMapper.completeClosureRequest(closure)).thenReturn(1);

        accountClosureService.approveClosure(7L, CLOSURE_REQUEST_ID);

        verifyNoInteractions(withdrawalService);
        assertThat(closure.getWithdrawalId()).isNull();
        assertThat(closure.getProcessedBy()).isEqualTo(7L);
        assertThat(closure.getProcessedAt()).isEqualTo(NOW);
        verify(accountMapper).completeClosure(ACCOUNT_ID);
        verify(accountClosureMapper).completeClosureRequest(closure);
        verify(accountLogService)
                .recordStatusChange(
                        argThat(
                                account ->
                                        account.getAccountId().equals(ACCOUNT_ID)
                                                && account.getStatus() == Status.CLOSED),
                        eq(Status.CLOSURE_REQUESTED),
                        eq(NOW),
                        eq("계좌 해지 완료"));
        assertAuditLog(7L, "CLOSURE_REQUESTED", "CLOSED", "ACCOUNT_CLOSURE_APPROVED");
    }

    @Test
    void positiveBalanceIsFullyWithdrawnBeforeAccountClosure() {
        AccountClosureDTO closure = closureForApproval(true);
        WithdrawalResultDTO withdrawalResult =
                WithdrawalResultDTO.builder().withdrawalId(40L).allocations(List.of()).build();
        when(accountClosureMapper.selectByIdForUpdate(CLOSURE_REQUEST_ID))
                .thenReturn(Optional.of(closure));
        when(accountMapper.selectByAccountIdForUpdate(ACCOUNT_ID))
                .thenReturn(
                        Optional.of(account(Status.CLOSURE_REQUESTED, "500")),
                        Optional.of(account(Status.CLOSURE_REQUESTED, "0")));
        when(withdrawalService.withdrawForClosure(any())).thenReturn(withdrawalResult);
        when(accountMapper.completeClosure(ACCOUNT_ID)).thenReturn(1);
        when(businessClockService.now()).thenReturn(NOW);
        when(accountClosureMapper.completeClosureRequest(closure)).thenReturn(1);

        accountClosureService.approveClosure(7L, CLOSURE_REQUEST_ID);

        ArgumentCaptor<WithdrawalRequestDTO> requestCaptor =
                ArgumentCaptor.forClass(WithdrawalRequestDTO.class);
        verify(withdrawalService).withdrawForClosure(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(requestCaptor.getValue().getRequestedAmount()).isEqualByComparingTo("500");
        assertThat(requestCaptor.getValue().isEarlyWithdrawalAgreed()).isTrue();
        assertThat(requestCaptor.getValue().getDestinationGeneralAccountId())
                .isEqualTo(GENERAL_ACCOUNT_ID);
        assertThat(closure.getWithdrawalId()).isEqualTo(40L);

        InOrder order = inOrder(withdrawalService, accountMapper, accountClosureMapper);
        order.verify(withdrawalService).withdrawForClosure(any());
        order.verify(accountMapper).selectByAccountIdForUpdate(ACCOUNT_ID);
        order.verify(accountMapper).completeClosure(ACCOUNT_ID);
        order.verify(accountClosureMapper).completeClosureRequest(closure);
    }

    @Test
    void remainingBalanceAfterForcedWithdrawalPreventsAccountClosure() {
        AccountClosureDTO closure = closureForApproval(true);
        WithdrawalResultDTO withdrawalResult =
                WithdrawalResultDTO.builder().withdrawalId(40L).allocations(List.of()).build();
        when(accountClosureMapper.selectByIdForUpdate(CLOSURE_REQUEST_ID))
                .thenReturn(Optional.of(closure));
        when(accountMapper.selectByAccountIdForUpdate(ACCOUNT_ID))
                .thenReturn(
                        Optional.of(account(Status.CLOSURE_REQUESTED, "500")),
                        Optional.of(account(Status.CLOSURE_REQUESTED, "1")));
        when(withdrawalService.withdrawForClosure(any())).thenReturn(withdrawalResult);

        assertThatThrownBy(() -> accountClosureService.approveClosure(7L, CLOSURE_REQUEST_ID))
                .isInstanceOf(AccountClosureProcessingException.class)
                .hasMessage("강제인출 후에도 계좌 잔액이 남아 있어 해지할 수 없습니다.");

        verify(accountMapper, never()).completeClosure(ACCOUNT_ID);
        verify(accountClosureMapper, never()).completeClosureRequest(any());
    }

    @Test
    void changedAccountStateBeforeClosurePreventsAccountClosure() {
        AccountClosureDTO closure = closureForApproval(false);
        when(accountClosureMapper.selectByIdForUpdate(CLOSURE_REQUEST_ID))
                .thenReturn(Optional.of(closure));
        when(accountMapper.selectByAccountIdForUpdate(ACCOUNT_ID))
                .thenReturn(
                        Optional.of(account(Status.CLOSURE_REQUESTED, "0")),
                        Optional.of(account(Status.OPENED, "0")));

        assertThatThrownBy(() -> accountClosureService.approveClosure(7L, CLOSURE_REQUEST_ID))
                .isInstanceOf(AccountClosureNotAllowedException.class)
                .hasMessage("해지 신청 상태가 변경되어 계좌를 해지할 수 없습니다.");

        verify(accountMapper, never()).completeClosure(ACCOUNT_ID);
        verify(accountClosureMapper, never()).completeClosureRequest(any());
    }

    @Test
    void conditionalAccountClosureFailureIsReportedSeparately() {
        AccountClosureDTO closure = closureForApproval(false);
        when(accountClosureMapper.selectByIdForUpdate(CLOSURE_REQUEST_ID))
                .thenReturn(Optional.of(closure));
        when(accountMapper.selectByAccountIdForUpdate(ACCOUNT_ID))
                .thenReturn(Optional.of(account(Status.CLOSURE_REQUESTED, "0")));
        when(accountMapper.completeClosure(ACCOUNT_ID)).thenReturn(0);

        assertThatThrownBy(() -> accountClosureService.approveClosure(7L, CLOSURE_REQUEST_ID))
                .isInstanceOf(AccountClosureProcessingException.class)
                .hasMessage("계좌 상태가 변경되어 해지 처리에 실패했습니다.");

        verify(accountClosureMapper, never()).completeClosureRequest(any());
    }

    @Test
    void forcedWithdrawalWithoutIdDoesNotCloseAccount() {
        AccountClosureDTO closure = closureForApproval(true);
        when(accountClosureMapper.selectByIdForUpdate(CLOSURE_REQUEST_ID))
                .thenReturn(Optional.of(closure));
        when(accountMapper.selectByAccountIdForUpdate(ACCOUNT_ID))
                .thenReturn(Optional.of(account(Status.CLOSURE_REQUESTED, "500")));
        when(withdrawalService.withdrawForClosure(any()))
                .thenReturn(WithdrawalResultDTO.builder().allocations(List.of()).build());

        assertThatThrownBy(() -> accountClosureService.approveClosure(7L, CLOSURE_REQUEST_ID))
                .isInstanceOf(AccountClosureProcessingException.class)
                .hasMessage("강제 인출 식별자를 확인할 수 없습니다.");

        verify(accountMapper, never()).completeClosure(ACCOUNT_ID);
        verify(accountClosureMapper, never()).completeClosureRequest(any());
    }

    @Test
    void accountOutsideClosureRequestedStateCannotBeApproved() {
        AccountClosureDTO closure = closureForApproval(true);
        when(accountClosureMapper.selectByIdForUpdate(CLOSURE_REQUEST_ID))
                .thenReturn(Optional.of(closure));
        when(accountMapper.selectByAccountIdForUpdate(ACCOUNT_ID))
                .thenReturn(Optional.of(account(Status.OPENED, "0")));

        assertThatThrownBy(() -> accountClosureService.approveClosure(7L, CLOSURE_REQUEST_ID))
                .isInstanceOf(AccountClosureNotAllowedException.class)
                .hasMessage("해지 신청 상태의 계좌만 승인할 수 있습니다.");

        verifyNoInteractions(withdrawalService);
        verify(accountMapper, never()).completeClosure(ACCOUNT_ID);
    }

    @Test
    void closureCompletionFailureRaisesProcessingException() {
        AccountClosureDTO closure = closureForApproval(false);
        when(accountClosureMapper.selectByIdForUpdate(CLOSURE_REQUEST_ID))
                .thenReturn(Optional.of(closure));
        when(accountMapper.selectByAccountIdForUpdate(ACCOUNT_ID))
                .thenReturn(Optional.of(account(Status.CLOSURE_REQUESTED, "0")));
        when(accountMapper.completeClosure(ACCOUNT_ID)).thenReturn(1);
        when(businessClockService.now()).thenReturn(NOW);
        when(accountClosureMapper.completeClosureRequest(closure)).thenReturn(0);

        assertThatThrownBy(() -> accountClosureService.approveClosure(7L, CLOSURE_REQUEST_ID))
                .isInstanceOf(AccountClosureProcessingException.class)
                .hasMessage("계좌 해지 신청 완료 처리에 실패했습니다.");
    }

    @Test
    void getClosuresConvertsEveryMapperResultWithoutChangingOrder() {
        AccountClosureDTO first = closureForApproval(true);
        first.setCustomerName("첫 번째 고객");
        first.setAccountNo("1234567890");
        AccountClosureDTO second =
                AccountClosureDTO.builder()
                        .closureRequestId(31L)
                        .accountId(2L)
                        .destinationGeneralAccountId(21L)
                        .status(AccountClosureStatus.REQUESTED)
                        .requestedAt(NOW.plusMinutes(1))
                        .customerName("두 번째 고객")
                        .accountNo("0987654321")
                        .build();
        when(accountClosureMapper.selectByStatus(AccountClosureStatus.REQUESTED))
                .thenReturn(List.of(first, second));

        List<AccountClosureResponseDTO> result =
                accountClosureService.getClosures(AccountClosureStatus.REQUESTED);

        assertThat(result)
                .extracting(AccountClosureResponseDTO::getClosureRequestId)
                .containsExactly(CLOSURE_REQUEST_ID, 31L);
        assertThat(result)
                .extracting(AccountClosureResponseDTO::getCustomerName)
                .containsExactly("첫 번째 고객", "두 번째 고객");
        assertThat(result)
                .extracting(AccountClosureResponseDTO::getAccountNo)
                .containsExactly("1234567890", "0987654321");
        verify(accountClosureMapper).selectByStatus(AccountClosureStatus.REQUESTED);
    }

    @Test
    void getClosureConvertsFoundClosure() {
        AccountClosureDTO closure = closureForApproval(true);
        closure.setRequestedAt(NOW);
        closure.setCustomerName("조회 고객");
        closure.setAccountNo("1234567890");
        closure.setAccountAmount(new BigDecimal("1000"));
        when(accountClosureMapper.selectById(CLOSURE_REQUEST_ID)).thenReturn(Optional.of(closure));
        when(withdrawalService.getImmaturePrincipalAmount(ACCOUNT_ID))
                .thenReturn(new BigDecimal("400"));

        AccountClosureDetailResponseDTO result =
                accountClosureService.getClosure(CLOSURE_REQUEST_ID);

        assertThat(result.getClosureRequestId()).isEqualTo(CLOSURE_REQUEST_ID);
        assertThat(result.getCustomerName()).isEqualTo("조회 고객");
        assertThat(result.getAccountNo()).isEqualTo("1234567890");
        assertThat(result.getAccountAmount()).isEqualByComparingTo("1000");
        assertThat(result.getDestinationGeneralAccountId()).isEqualTo(GENERAL_ACCOUNT_ID);
        assertThat(result.isEarlyWithdrawalAgreed()).isTrue();
        assertThat(result.isHasImmaturePrincipal()).isTrue();
        assertThat(result.getImmaturePrincipalAmount()).isEqualByComparingTo("400");
        assertThat(result.isTaxBenefitCancellationExpected()).isTrue();
        assertThat(result.getStatus()).isEqualTo(AccountClosureStatus.REQUESTED);
        assertThat(result.getRequestedAt()).isEqualTo(NOW);
    }

    @Test
    void getClosureSeparatesConsentFromActualImmaturePrincipal() {
        AccountClosureDTO closure = closureForApproval(true);
        when(accountClosureMapper.selectById(CLOSURE_REQUEST_ID)).thenReturn(Optional.of(closure));
        when(withdrawalService.getImmaturePrincipalAmount(ACCOUNT_ID)).thenReturn(BigDecimal.ZERO);

        AccountClosureDetailResponseDTO result =
                accountClosureService.getClosure(CLOSURE_REQUEST_ID);

        assertThat(result.isEarlyWithdrawalAgreed()).isTrue();
        assertThat(result.isHasImmaturePrincipal()).isFalse();
        assertThat(result.getImmaturePrincipalAmount()).isZero();
        assertThat(result.isTaxBenefitCancellationExpected()).isFalse();
    }

    @Test
    void completedClosureUsesActualImmatureWithdrawalHistory() {
        AccountClosureDTO closure = closureForApproval(true);
        closure.setStatus(AccountClosureStatus.COMPLETED);
        closure.setWithdrawalId(55L);
        when(accountClosureMapper.selectById(CLOSURE_REQUEST_ID)).thenReturn(Optional.of(closure));
        when(withdrawalService.getImmatureAllocatedAmount(55L)).thenReturn(new BigDecimal("400"));

        AccountClosureDetailResponseDTO result =
                accountClosureService.getClosure(CLOSURE_REQUEST_ID);

        assertThat(result.getImmaturePrincipalAmount()).isEqualByComparingTo("400");
        assertThat(result.isTaxBenefitCancellationExpected()).isFalse();
        assertThat(result.isTaxBenefitCancellationOccurred()).isTrue();
        verify(withdrawalService, never()).getImmaturePrincipalAmount(ACCOUNT_ID);
    }

    @Test
    void rejectedClosureReportsNoActualTaxBenefitCancellation() {
        AccountClosureDTO closure = closureForApproval(true);
        closure.setStatus(AccountClosureStatus.REJECTED);
        when(accountClosureMapper.selectById(CLOSURE_REQUEST_ID)).thenReturn(Optional.of(closure));

        AccountClosureDetailResponseDTO result =
                accountClosureService.getClosure(CLOSURE_REQUEST_ID);

        assertThat(result.getImmaturePrincipalAmount()).isZero();
        assertThat(result.isTaxBenefitCancellationExpected()).isFalse();
        assertThat(result.isTaxBenefitCancellationOccurred()).isFalse();
        verifyNoInteractions(withdrawalService);
    }

    @Test
    void getClosureThrowsNotFoundForUnknownId() {
        when(accountClosureMapper.selectById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountClosureService.getClosure(999L))
                .isInstanceOf(AccountClosureNotFoundException.class)
                .hasMessage("계좌 해지 신청을 찾을 수 없습니다.");
    }

    private void assertAuditLog(
            Long adminId, String beforeValue, String afterValue, String reasonCode) {
        ArgumentCaptor<AuditLogDTO> auditCaptor = ArgumentCaptor.forClass(AuditLogDTO.class);
        verify(auditLogService).log(auditCaptor.capture());

        assertThat(auditCaptor.getValue())
                .satisfies(
                        auditLog -> {
                            assertThat(auditLog.getAdminId()).isEqualTo(adminId);
                            assertThat(auditLog.getTargetTable()).isEqualTo("ACCOUNT");
                            assertThat(auditLog.getTargetPk())
                                    .isEqualTo(String.valueOf(ACCOUNT_ID));
                            assertThat(auditLog.getBeforeValue()).isEqualTo(beforeValue);
                            assertThat(auditLog.getAfterValue()).isEqualTo(afterValue);
                            assertThat(auditLog.getReasonCode()).isEqualTo(reasonCode);
                            // 감사로그 processed_at은 업무시각이 아니라 DB CURRENT_TIMESTAMP로 기록된다.
                            assertThat(auditLog.getProcessedAt()).isNull();
                        });
    }

    private void prepareAccountAndCi() {
        when(accountMapper.selectByCustomerId(CUSTOMER_ID))
                .thenReturn(Optional.of(account(Status.OPENED)));
        when(accountMapper.selectCiHashByCustomerId(CUSTOMER_ID))
                .thenReturn(Optional.of("customer-ci-hash"));
    }

    private void prepareExternalValidationSuccess() {
        prepareAccountAndCi();
        when(generalAccountClient.verifyGeneralAccount(any()))
                .thenReturn(generalAccount(GeneralAccountStatus.ACTIVE));
    }

    private void prepareClosureInsertSuccess() {
        doAnswer(
                        invocation -> {
                            AccountClosureDTO closure = invocation.getArgument(0);
                            closure.setClosureRequestId(CLOSURE_REQUEST_ID);
                            return 1;
                        })
                .when(accountClosureMapper)
                .insertClosureRequest(any(AccountClosureDTO.class));
    }

    private static AccountDTO account(Status status) {
        return account(status, "0");
    }

    private static AccountDTO account(Status status, String amount) {
        return AccountDTO.builder()
                .accountId(ACCOUNT_ID)
                .customerId(CUSTOMER_ID)
                .status(status)
                .amount(new BigDecimal(amount))
                .build();
    }

    private static AccountClosureDTO closure(AccountClosureStatus status) {
        return AccountClosureDTO.builder()
                .closureRequestId(CLOSURE_REQUEST_ID)
                .accountId(ACCOUNT_ID)
                .status(status)
                .build();
    }

    private static AccountClosureDTO closureForApproval(boolean earlyWithdrawalAgreed) {
        return AccountClosureDTO.builder()
                .closureRequestId(CLOSURE_REQUEST_ID)
                .accountId(ACCOUNT_ID)
                .destinationGeneralAccountId(GENERAL_ACCOUNT_ID)
                .earlyWithdrawalAgreed(earlyWithdrawalAgreed)
                .status(AccountClosureStatus.REQUESTED)
                .build();
    }

    private static GeneralAccountResponseDTO generalAccount(GeneralAccountStatus status) {
        return GeneralAccountResponseDTO.builder()
                .generalAccountId(GENERAL_ACCOUNT_ID)
                .accountNo("110-123-456789")
                .status(status)
                .build();
    }

    private static AccountClosureApplyRequestDTO request(boolean earlyWithdrawalAgreed) {
        return AccountClosureApplyRequestDTO.builder()
                .destinationGeneralAccountId(GENERAL_ACCOUNT_ID)
                .earlyWithdrawalAgreed(earlyWithdrawalAgreed)
                .build();
    }
}
