package com.app.maria.domain.withdrawal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.app.maria.domain.account.dto.AccountBenefitLogDTO;
import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountBenefitLogMapper;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.account.type.BenefitType;
import com.app.maria.domain.account.type.Status;
import com.app.maria.domain.withdrawal.dto.LeftAmountDTO;
import com.app.maria.domain.withdrawal.dto.WithdrawalAllocationDTO;
import com.app.maria.domain.withdrawal.dto.WithdrawalDTO;
import com.app.maria.domain.withdrawal.dto.WithdrawalResultDTO;
import com.app.maria.domain.withdrawal.dto.request.WithdrawalRequestDTO;
import com.app.maria.domain.withdrawal.mapper.WithdrawalMapper;
import com.app.maria.domain.withdrawal.type.WithdrawalStatus;
import com.app.maria.domain.withdrawal.type.WithdrawalType;
import com.app.maria.global.client.generalaccount.dto.response.GeneralAccountResponseDTO;
import com.app.maria.global.client.generalaccount.type.GeneralAccountStatus;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
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
class WithdrawalProcessorTest {

    private static final Long ACCOUNT_ID = 1L;
    private static final Long CUSTOMER_ID = 10L;
    private static final Long GENERAL_ACCOUNT_ID = 20L;
    private static final Long WITHDRAWAL_ID = 30L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 6, 10, 0);

    @Mock BusinessClockService businessClockService;
    @Mock AccountMapper accountMapper;
    @Mock WithdrawalMapper withdrawalMapper;
    @Mock AccountBenefitLogMapper accountBenefitLogMapper;
    @Mock ClosureWithdrawalContextHolder closureWithdrawalContextHolder;
    @InjectMocks WithdrawalProcessor withdrawalService;

    @Test
    void nonOpenedAccount_stopsBeforeFifoCalculation() {
        when(accountMapper.selectByAccountIdForUpdate(ACCOUNT_ID))
                .thenReturn(Optional.of(account(Status.APPLIED)));

        assertThatThrownBy(() -> withdrawalService.withdraw(request("100"), verifiedAccount()))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.ACCOUNT_STATUS_NOT_WITHDRAWABLE.getMessage());
        verifyNoInteractions(withdrawalMapper, businessClockService);
    }

    @Test
    void regularWithdrawalRejectsClosureRequestedAccount() {
        when(accountMapper.selectByAccountIdForUpdate(ACCOUNT_ID))
                .thenReturn(Optional.of(account(Status.CLOSURE_REQUESTED, "300")));

        assertThatThrownBy(() -> withdrawalService.withdraw(request("100"), verifiedAccount()))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.ACCOUNT_STATUS_NOT_WITHDRAWABLE.getMessage());

        verifyNoInteractions(withdrawalMapper, businessClockService);
    }

    @Test
    void closureWithdrawalAllowsClosureRequestedAccount() {
        AccountDTO closureRequestedAccount = account(Status.CLOSURE_REQUESTED, "300");
        when(accountMapper.selectByAccountIdForUpdate(ACCOUNT_ID))
                .thenReturn(Optional.of(closureRequestedAccount));
        when(withdrawalMapper.selectAvailableLeftAmountsByAccountId(ACCOUNT_ID))
                .thenReturn(List.of(leftAmount(10L, "300", NOW.minusYears(2))));
        when(withdrawalMapper.selectProvisionalAmountByAccountId(ACCOUNT_ID))
                .thenReturn(BigDecimal.ZERO);
        when(businessClockService.now()).thenReturn(NOW);
        preparePersistenceSuccess();

        WithdrawalResultDTO result =
                withdrawalService.withdrawForClosure(request("300"), verifiedAccount());

        assertThat(result.getWithdrawalId()).isEqualTo(WITHDRAWAL_ID);
        assertThat(result.getAllocations()).singleElement();
        verify(withdrawalMapper).deductAccountAmount(ACCOUNT_ID, new BigDecimal("300"));
        verify(withdrawalMapper).updateWithdrawalStatus(WITHDRAWAL_ID, WithdrawalStatus.COMPLETED);
    }

    @Test
    void closureWithdrawal_statusCheckPasses_setsContext() {
        // 상태 검사 통과 후 컨텍스트가 설정되어야 한다
        when(accountMapper.selectByAccountIdForUpdate(ACCOUNT_ID))
                .thenReturn(Optional.of(account(Status.CLOSURE_REQUESTED, "300")));
        when(withdrawalMapper.selectAvailableLeftAmountsByAccountId(ACCOUNT_ID))
                .thenReturn(List.of(leftAmount(10L, "300", NOW.minusYears(2))));
        when(withdrawalMapper.selectProvisionalAmountByAccountId(ACCOUNT_ID))
                .thenReturn(BigDecimal.ZERO);
        when(businessClockService.now()).thenReturn(NOW);
        preparePersistenceSuccess();

        withdrawalService.withdrawForClosure(request("300"), verifiedAccount());

        verify(closureWithdrawalContextHolder)
                .set(ACCOUNT_ID, new BigDecimal("300"), "110-123-456789", GENERAL_ACCOUNT_ID);
    }

    @Test
    void closureWithdrawal_statusCheckFails_doesNotSetContext() {
        // 상태 불일치(이미 처리됨 등)는 인출 시도 전이므로 컨텍스트를 설정하지 않는다
        when(accountMapper.selectByAccountIdForUpdate(ACCOUNT_ID))
                .thenReturn(Optional.of(account(Status.OPENED, "300")));

        assertThatThrownBy(
                        () ->
                                withdrawalService.withdrawForClosure(
                                        request("300"), verifiedAccount()))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.ACCOUNT_STATUS_NOT_WITHDRAWABLE.getMessage());

        verify(closureWithdrawalContextHolder, never()).set(any(), any(), any(), any());
    }

    @Test
    void exactlyOneYearAfterFinalAt_isMatured() {
        prepareOpenedAccount(List.of(leftAmount(11L, "300", NOW.minusYears(1))));

        WithdrawalResultDTO result = withdrawalService.withdraw(request("200"), verifiedAccount());

        assertThat(result.getAllocations())
                .singleElement()
                .satisfies(
                        allocation -> {
                            assertThat(allocation.getLeftAmountId()).isEqualTo(11L);
                            assertThat(allocation.getAllocatedAmount()).isEqualByComparingTo("200");
                            assertThat(allocation.getWithdrawalAt()).isEqualTo(NOW);
                            assertThat(allocation.getType())
                                    .isEqualTo(WithdrawalType.MATURED_PRINCIPAL_INCLUDED);
                        });
    }

    @Test
    void oneSecondBeforeOneYear_requiresEarlyWithdrawalConsent() {
        prepareOpenedAccount(List.of(leftAmount(12L, "300", NOW.minusYears(1).plusSeconds(1))));

        assertThatThrownBy(() -> withdrawalService.withdraw(request("200"), verifiedAccount()))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.EARLY_WITHDRAWAL_CONSENT_REQUIRED.getMessage());

        verify(accountMapper, never()).updateBenefitToImpossible(ACCOUNT_ID);
        verifyNoInteractions(accountBenefitLogMapper);
    }

    @Test
    void agreedEarlyWithdrawal_allocatesImmaturePrincipalFifoAndSavesBenefitLog() {
        prepareOpenedAccount(
                "1200",
                BenefitType.POSSIBLE,
                List.of(
                        leftAmount(61L, "300", NOW.minusYears(2)),
                        leftAmount(62L, "400", NOW.minusMonths(11)),
                        leftAmount(63L, "500", NOW.minusMonths(6))));
        when(accountMapper.updateBenefitToImpossible(ACCOUNT_ID)).thenReturn(1);
        when(accountBenefitLogMapper.insertLog(org.mockito.ArgumentMatchers.any())).thenReturn(1);

        WithdrawalResultDTO result =
                withdrawalService.withdraw(request("800", true), verifiedAccount());

        assertThat(result.getAllocations())
                .extracting(WithdrawalAllocationDTO::getLeftAmountId)
                .containsExactly(61L, 62L, 63L);
        assertThat(result.getAllocations())
                .extracting(WithdrawalAllocationDTO::getAllocatedAmount)
                .containsExactly(
                        new BigDecimal("300"), new BigDecimal("400"), new BigDecimal("100"));
        assertThat(result.getAllocations())
                .extracting(WithdrawalAllocationDTO::getType)
                .containsExactly(
                        WithdrawalType.MATURED_PRINCIPAL_INCLUDED,
                        WithdrawalType.IMMATURE_PRINCIPAL_INCLUDED,
                        WithdrawalType.IMMATURE_PRINCIPAL_INCLUDED);

        ArgumentCaptor<AccountBenefitLogDTO> logCaptor =
                ArgumentCaptor.forClass(AccountBenefitLogDTO.class);
        verify(accountBenefitLogMapper).insertLog(logCaptor.capture());

        assertThat(logCaptor.getValue())
                .satisfies(
                        log -> {
                            assertThat(log.getBenefitId()).isNull();
                            assertThat(log.getAccountId()).isEqualTo(ACCOUNT_ID);
                            assertThat(log.getPrevStatus()).isEqualTo(BenefitType.POSSIBLE);
                            assertThat(log.getNewStatus()).isEqualTo(BenefitType.IMPOSSIBLE);
                            assertThat(log.getChangedAt()).isEqualTo(NOW);
                            assertThat(log.getReason()).isEqualTo("조기인출로 인한 세제혜택 취소");
                        });
    }

    @Test
    void benefitAlreadyImpossible_doesNotSaveDuplicateBenefitLog() {
        prepareOpenedAccount(
                "300", BenefitType.IMPOSSIBLE, List.of(leftAmount(71L, "300", NOW.minusMonths(3))));
        when(accountMapper.updateBenefitToImpossible(ACCOUNT_ID)).thenReturn(0);

        WithdrawalResultDTO result =
                withdrawalService.withdraw(request("200", true), verifiedAccount());

        assertThat(result.getAllocations())
                .singleElement()
                .satisfies(
                        allocation -> {
                            assertThat(allocation.getLeftAmountId()).isEqualTo(71L);
                            assertThat(allocation.getAllocatedAmount()).isEqualByComparingTo("200");
                            assertThat(allocation.getType())
                                    .isEqualTo(WithdrawalType.IMMATURE_PRINCIPAL_INCLUDED);
                        });
        verifyNoInteractions(accountBenefitLogMapper);
    }

    @Test
    void benefitLogInsertFailure_abortsEarlyWithdrawal() {
        prepareOpenedAccount(
                "300", BenefitType.REDUCED, List.of(leftAmount(81L, "300", NOW.minusMonths(3))));
        when(accountMapper.updateBenefitToImpossible(ACCOUNT_ID)).thenReturn(1);
        when(accountBenefitLogMapper.insertLog(org.mockito.ArgumentMatchers.any())).thenReturn(0);

        assertThatThrownBy(
                        () -> withdrawalService.withdraw(request("200", true), verifiedAccount()))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.ACCOUNT_BENEFIT_LOG_SAVE_FAILED.getMessage());
    }

    @Test
    void multipleSources_areAllocatedInFifoOrderWithPartialLastSource() {
        prepareOpenedAccount(
                List.of(
                        leftAmount(21L, "300", NOW.minusYears(2)),
                        leftAmount(22L, "500", NOW.minusYears(1).minusDays(1))));

        WithdrawalResultDTO result = withdrawalService.withdraw(request("700"), verifiedAccount());

        assertThat(result.getAllocations())
                .extracting(WithdrawalAllocationDTO::getLeftAmountId)
                .containsExactly(21L, 22L);
        assertThat(result.getAllocations())
                .extracting(WithdrawalAllocationDTO::getAllocatedAmount)
                .containsExactly(new BigDecimal("300"), new BigDecimal("400"));

        InOrder order = inOrder(accountMapper, withdrawalMapper, businessClockService);
        order.verify(accountMapper).selectByAccountIdForUpdate(ACCOUNT_ID);
        order.verify(businessClockService).now();
        order.verify(withdrawalMapper).selectAvailableLeftAmountsByAccountId(ACCOUNT_ID);
    }

    @Test
    void requestWithinEarnings_isAllocatedWithoutAPrincipalSource() {
        prepareOpenedAccount("1000", List.of(leftAmount(31L, "700", NOW.minusYears(2))));

        WithdrawalResultDTO result = withdrawalService.withdraw(request("200"), verifiedAccount());

        assertThat(result.getAllocations())
                .singleElement()
                .satisfies(
                        allocation -> {
                            assertThat(allocation.getLeftAmountId()).isNull();
                            assertThat(allocation.getAllocatedAmount()).isEqualByComparingTo("200");
                            assertThat(allocation.getType())
                                    .isEqualTo(WithdrawalType.EARNINGS_ONLY);
                            assertThat(allocation.getWithdrawalAt()).isEqualTo(NOW);
                            assertThat(allocation.getWithdrawalId()).isEqualTo(WITHDRAWAL_ID);
                        });

        verify(withdrawalMapper, never()).deductLeftAmount(anyLong(), any());
        verify(withdrawalMapper).deductAccountAmount(ACCOUNT_ID, new BigDecimal("200"));
        verify(withdrawalMapper).updateWithdrawalStatus(WITHDRAWAL_ID, WithdrawalStatus.COMPLETED);
    }

    @Test
    void requestExceedingEarnings_allocatesEarningsThenMaturedPrincipal() {
        prepareOpenedAccount(
                "1000",
                List.of(
                        leftAmount(41L, "300", NOW.minusYears(2)),
                        leftAmount(42L, "500", NOW.minusYears(1).minusDays(1))));

        WithdrawalResultDTO result = withdrawalService.withdraw(request("450"), verifiedAccount());

        assertThat(result.getAllocations())
                .extracting(WithdrawalAllocationDTO::getLeftAmountId)
                .containsExactly(null, 41L);
        assertThat(result.getAllocations())
                .extracting(WithdrawalAllocationDTO::getAllocatedAmount)
                .containsExactly(new BigDecimal("200"), new BigDecimal("250"));
        assertThat(result.getAllocations())
                .extracting(WithdrawalAllocationDTO::getType)
                .containsExactly(
                        WithdrawalType.EARNINGS_ONLY, WithdrawalType.MATURED_PRINCIPAL_INCLUDED);
    }

    @Test
    void negativeCalculatedEarnings_isTreatedAsZero() {
        prepareOpenedAccount("500", List.of(leftAmount(51L, "600", NOW.minusYears(2))));

        WithdrawalResultDTO result = withdrawalService.withdraw(request("100"), verifiedAccount());

        assertThat(result.getAllocations())
                .singleElement()
                .satisfies(
                        allocation -> {
                            assertThat(allocation.getLeftAmountId()).isEqualTo(51L);
                            assertThat(allocation.getAllocatedAmount()).isEqualByComparingTo("100");
                            assertThat(allocation.getType())
                                    .isEqualTo(WithdrawalType.MATURED_PRINCIPAL_INCLUDED);
                        });
    }

    @Test
    void requestExceedingAccountAmount_isRejectedBeforeLoadingSources() {
        when(accountMapper.selectByAccountIdForUpdate(ACCOUNT_ID))
                .thenReturn(Optional.of(account(Status.OPENED, "500")));
        when(businessClockService.now()).thenReturn(NOW);

        assertThatThrownBy(() -> withdrawalService.withdraw(request("501"), verifiedAccount()))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.INSUFFICIENT_WITHDRAWAL_AMOUNT.getMessage());

        verifyNoInteractions(withdrawalMapper);
        verify(businessClockService).now();
    }

    @Test
    void zeroAmountRequest_isRejectedBeforeLoadingSources() {
        assertThatThrownBy(() -> withdrawalService.withdraw(request("0"), verifiedAccount()))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.INVALID_WITHDRAWAL_AMOUNT.getMessage());
        verifyNoInteractions(accountMapper);
        verifyNoInteractions(withdrawalMapper, businessClockService);
    }

    @Test
    void withdrawalInsertFailure_stopsBeforeAllocationAndBalanceUpdates() {
        prepareOpenedAccount(List.of(leftAmount(91L, "300", NOW.minusYears(2))));
        when(withdrawalMapper.insertWithdrawal(any(WithdrawalDTO.class))).thenReturn(0);

        assertThatThrownBy(() -> withdrawalService.withdraw(request("200"), verifiedAccount()))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.WITHDRAWAL_PROCESSING_FAILED.getMessage());

        verify(withdrawalMapper, never())
                .insertWithdrawalAllocation(any(WithdrawalAllocationDTO.class));
        verify(withdrawalMapper, never()).deductLeftAmount(anyLong(), any());
        verify(withdrawalMapper, never()).deductAccountAmount(anyLong(), any());
        verify(withdrawalMapper, never())
                .updateWithdrawalStatus(anyLong(), any(WithdrawalStatus.class));
    }

    @Test
    void allocationInsertFailure_stopsBeforePrincipalAndAccountDeductions() {
        prepareOpenedAccount(List.of(leftAmount(92L, "300", NOW.minusYears(2))));
        when(withdrawalMapper.insertWithdrawalAllocation(any(WithdrawalAllocationDTO.class)))
                .thenReturn(0);

        assertThatThrownBy(() -> withdrawalService.withdraw(request("200"), verifiedAccount()))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.WITHDRAWAL_PROCESSING_FAILED.getMessage());

        verify(withdrawalMapper, never()).deductLeftAmount(anyLong(), any());
        verify(withdrawalMapper, never()).deductAccountAmount(anyLong(), any());
        verify(withdrawalMapper, never())
                .updateWithdrawalStatus(anyLong(), any(WithdrawalStatus.class));
    }

    @Test
    void principalDeductionFailure_stopsBeforeAccountDeductionAndCompletion() {
        prepareOpenedAccount(List.of(leftAmount(93L, "300", NOW.minusYears(2))));
        when(withdrawalMapper.deductLeftAmount(93L, new BigDecimal("200"))).thenReturn(0);

        assertThatThrownBy(() -> withdrawalService.withdraw(request("200"), verifiedAccount()))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.WITHDRAWAL_PROCESSING_FAILED.getMessage());

        verify(withdrawalMapper, never()).deductAccountAmount(anyLong(), any());
        verify(withdrawalMapper, never())
                .updateWithdrawalStatus(anyLong(), any(WithdrawalStatus.class));
    }

    @Test
    void accountDeductionFailure_doesNotMarkWithdrawalCompleted() {
        prepareOpenedAccount(List.of(leftAmount(94L, "300", NOW.minusYears(2))));
        when(withdrawalMapper.deductAccountAmount(ACCOUNT_ID, new BigDecimal("200"))).thenReturn(0);

        assertThatThrownBy(() -> withdrawalService.withdraw(request("200"), verifiedAccount()))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.WITHDRAWAL_PROCESSING_FAILED.getMessage());

        verify(withdrawalMapper, never())
                .updateWithdrawalStatus(anyLong(), any(WithdrawalStatus.class));
    }

    @Test
    void persistedWithdrawalUsesVerifiedDestinationAndGeneratedIdForAllocation() {
        prepareOpenedAccount(List.of(leftAmount(95L, "300", NOW.minusYears(2))));

        withdrawalService.withdraw(request("200"), verifiedAccount());

        ArgumentCaptor<WithdrawalDTO> withdrawalCaptor =
                ArgumentCaptor.forClass(WithdrawalDTO.class);
        ArgumentCaptor<WithdrawalAllocationDTO> allocationCaptor =
                ArgumentCaptor.forClass(WithdrawalAllocationDTO.class);
        verify(withdrawalMapper).insertWithdrawal(withdrawalCaptor.capture());
        verify(withdrawalMapper).insertWithdrawalAllocation(allocationCaptor.capture());

        assertThat(withdrawalCaptor.getValue())
                .satisfies(
                        withdrawal -> {
                            assertThat(withdrawal.getAccountId()).isEqualTo(ACCOUNT_ID);
                            assertThat(withdrawal.getDestinationGeneralAccountId())
                                    .isEqualTo(GENERAL_ACCOUNT_ID);
                            assertThat(withdrawal.getDestinationAccountNo())
                                    .isEqualTo("110-123-456789");
                            assertThat(withdrawal.getProcessedAt()).isEqualTo(NOW);
                        });
        assertThat(allocationCaptor.getValue().getWithdrawalId()).isEqualTo(WITHDRAWAL_ID);
    }

    @Test
    void allBalanceIsProvisional_blocksWithdrawalBeforeFifo() {
        // 잔액 100만원이 전부 가환전 중 — FINALIZED 원금 없음
        AccountDTO account = account(Status.OPENED, "1000000");
        when(accountMapper.selectByAccountIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(withdrawalMapper.selectAvailableLeftAmountsByAccountId(ACCOUNT_ID))
                .thenReturn(List.of());
        when(withdrawalMapper.selectProvisionalAmountByAccountId(ACCOUNT_ID))
                .thenReturn(new BigDecimal("1000000"));
        when(businessClockService.now()).thenReturn(NOW);

        assertThatThrownBy(() -> withdrawalService.withdraw(request("100000"), verifiedAccount()))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.WITHDRAWAL_BLOCKED_BY_PROVISIONAL.getMessage());

        verify(withdrawalMapper, never()).insertWithdrawal(any());
    }

    @Test
    void partialProvisional_allowsWithdrawalWithinAvailableAmount() {
        // 잔액 1000 중 400이 가환전 중 → 인출 가능액 600
        // FINALIZED 원금 600 (1년 경과), 요청 500 → 성공
        AccountDTO account = account(Status.OPENED, "1000");
        when(accountMapper.selectByAccountIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(withdrawalMapper.selectAvailableLeftAmountsByAccountId(ACCOUNT_ID))
                .thenReturn(List.of(leftAmount(99L, "600", NOW.minusYears(2))));
        when(withdrawalMapper.selectProvisionalAmountByAccountId(ACCOUNT_ID))
                .thenReturn(new BigDecimal("400"));
        when(businessClockService.now()).thenReturn(NOW);
        preparePersistenceSuccess();

        WithdrawalResultDTO result = withdrawalService.withdraw(request("500"), verifiedAccount());

        assertThat(result.getAllocations())
                .singleElement()
                .satisfies(
                        allocation -> {
                            assertThat(allocation.getLeftAmountId()).isEqualTo(99L);
                            assertThat(allocation.getAllocatedAmount()).isEqualByComparingTo("500");
                            assertThat(allocation.getType())
                                    .isEqualTo(WithdrawalType.MATURED_PRINCIPAL_INCLUDED);
                        });
    }

    private void prepareOpenedAccount(List<LeftAmountDTO> leftAmounts) {
        BigDecimal principal =
                leftAmounts.stream()
                        .map(LeftAmountDTO::getCurAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        prepareOpenedAccount(principal.toPlainString(), leftAmounts);
    }

    private void prepareOpenedAccount(String accountAmount, List<LeftAmountDTO> leftAmounts) {
        prepareOpenedAccount(accountAmount, BenefitType.POSSIBLE, leftAmounts);
    }

    private void prepareOpenedAccount(
            String accountAmount, BenefitType benefit, List<LeftAmountDTO> leftAmounts) {
        prepareOpenedAccount(accountAmount, benefit, leftAmounts, BigDecimal.ZERO);
    }

    private void prepareOpenedAccount(
            String accountAmount,
            BenefitType benefit,
            List<LeftAmountDTO> leftAmounts,
            BigDecimal provisionalTotal) {
        AccountDTO openedAccount = account(Status.OPENED, accountAmount, benefit);
        when(accountMapper.selectByAccountIdForUpdate(ACCOUNT_ID))
                .thenReturn(Optional.of(openedAccount));
        when(withdrawalMapper.selectAvailableLeftAmountsByAccountId(ACCOUNT_ID))
                .thenReturn(leftAmounts);
        when(withdrawalMapper.selectProvisionalAmountByAccountId(ACCOUNT_ID))
                .thenReturn(provisionalTotal);
        when(businessClockService.now()).thenReturn(NOW);
        preparePersistenceSuccess();
    }

    private void preparePersistenceSuccess() {
        lenient()
                .doAnswer(
                        invocation -> {
                            WithdrawalDTO withdrawal = invocation.getArgument(0);
                            withdrawal.setWithdrawalId(WITHDRAWAL_ID);
                            return 1;
                        })
                .when(withdrawalMapper)
                .insertWithdrawal(any(WithdrawalDTO.class));
        lenient()
                .when(
                        withdrawalMapper.insertWithdrawalAllocation(
                                any(WithdrawalAllocationDTO.class)))
                .thenReturn(1);
        lenient().when(withdrawalMapper.deductLeftAmount(anyLong(), any())).thenReturn(1);
        lenient().when(withdrawalMapper.deductAccountAmount(eq(ACCOUNT_ID), any())).thenReturn(1);
        lenient()
                .when(
                        withdrawalMapper.updateWithdrawalStatus(
                                WITHDRAWAL_ID, WithdrawalStatus.COMPLETED))
                .thenReturn(1);
    }

    private static GeneralAccountResponseDTO verifiedAccount() {
        return GeneralAccountResponseDTO.builder()
                .generalAccountId(GENERAL_ACCOUNT_ID)
                .accountNo("110-123-456789")
                .status(GeneralAccountStatus.ACTIVE)
                .build();
    }

    private static AccountDTO account(Status status) {
        return account(status, "0");
    }

    private static AccountDTO account(Status status, String amount) {
        return account(status, amount, BenefitType.POSSIBLE);
    }

    private static AccountDTO account(Status status, String amount, BenefitType benefit) {
        return AccountDTO.builder()
                .accountId(ACCOUNT_ID)
                .customerId(CUSTOMER_ID)
                .status(status)
                .amount(new BigDecimal(amount))
                .benefit(benefit)
                .build();
    }

    private static WithdrawalRequestDTO request(String amount) {
        return request(amount, false);
    }

    private static WithdrawalRequestDTO request(String amount, boolean earlyWithdrawalAgreed) {
        return WithdrawalRequestDTO.builder()
                .accountId(ACCOUNT_ID)
                .requestedAmount(new BigDecimal(amount))
                .earlyWithdrawalAgreed(earlyWithdrawalAgreed)
                .destinationGeneralAccountId(GENERAL_ACCOUNT_ID)
                .build();
    }

    private static LeftAmountDTO leftAmount(Long id, String amount, LocalDateTime finalAt) {
        return LeftAmountDTO.builder()
                .leftAmountId(id)
                .exchangeId(id + 100L)
                .curAmount(new BigDecimal(amount))
                .finalAt(finalAt)
                .build();
    }
}
