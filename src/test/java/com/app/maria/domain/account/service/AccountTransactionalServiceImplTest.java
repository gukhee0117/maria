package com.app.maria.domain.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.dto.request.AccountReapplyRequestDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.account.type.AuditLogReasonCode;
import com.app.maria.domain.account.type.BenefitType;
import com.app.maria.domain.account.type.Status;
import com.app.maria.global.audit.dto.AuditLogDTO;
import com.app.maria.global.audit.service.AuditLogService;
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
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class AccountTransactionalServiceImplTest {
    private static final Long CUSTOMER_ID = 1L;
    private static final Long ACCOUNT_ID = 10L;
    private static final Long ADMIN_ID = 99L;
    private static final BigDecimal LIMIT = BigDecimal.valueOf(30_000_000L);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 2, 10, 30);

    @Mock private AccountMapper accountMapper;
    @Mock private AccountLogService accountLogService;
    @Mock private AuditLogService auditLogService;
    @InjectMocks private AccountTransactionalServiceImpl service;

    @Test
    void updateLimitRejectsAmountBelowOwnUsedAndReservedAmount() {
        when(accountMapper.selectByCustomerId(CUSTOMER_ID))
                .thenReturn(Optional.of(account(Status.OPENED, LIMIT)));
        when(accountMapper.selectOwnUsedAndReservedAmount(ACCOUNT_ID))
                .thenReturn(BigDecimal.valueOf(20_000_000L));

        assertThatThrownBy(
                        () ->
                                service.updateLimit(
                                        ADMIN_ID,
                                        CUSTOMER_ID,
                                        LIMIT,
                                        BigDecimal.valueOf(10_000_000L),
                                        NOW))
                .isInstanceOf(AppException.class)
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception ->
                                assertThat(exception.getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_LIMIT_BELOW_USED_AMOUNT));

        verify(accountMapper, never())
                .updateLimit(
                        anyLong(), any(Status.class), any(BigDecimal.class), any(BigDecimal.class));
    }

    @Test
    void updateLimitRejectsUnsupportedAccountStateWithDedicatedError() {
        when(accountMapper.selectByCustomerId(CUSTOMER_ID))
                .thenReturn(Optional.of(account(Status.REJECTED, LIMIT)));

        assertThatThrownBy(
                        () ->
                                service.updateLimit(
                                        ADMIN_ID,
                                        CUSTOMER_ID,
                                        LIMIT,
                                        BigDecimal.valueOf(40_000_000L),
                                        NOW))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception ->
                                assertThat(exception.getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_STATE_NOT_ALLOWED));
    }

    @Test
    void updateLimitRejectsUnchangedLimitWithDedicatedError() {
        when(accountMapper.selectByCustomerId(CUSTOMER_ID))
                .thenReturn(Optional.of(account(Status.OPENED, LIMIT)));

        assertThatThrownBy(() -> service.updateLimit(ADMIN_ID, CUSTOMER_ID, LIMIT, LIMIT, NOW))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception ->
                                assertThat(exception.getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_LIMIT_UNCHANGED));
    }

    @Test
    void applyReportsApplicationSaveFailureWithDedicatedError() {
        when(accountMapper.existsByCustomerId(CUSTOMER_ID)).thenReturn(false);
        when(accountMapper.insertApplication(any(AccountDTO.class))).thenReturn(0);

        assertThatThrownBy(
                        () -> service.apply(ADMIN_ID, account(Status.APPLIED, LIMIT), NOW, false))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception ->
                                assertThat(exception.getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_APPLICATION_SAVE_FAILED));
    }

    @Test
    void updateLimitRejectsStaleExpectedCurrentLimitBeforeUpdating() {
        when(accountMapper.selectByCustomerId(CUSTOMER_ID))
                .thenReturn(Optional.of(account(Status.OPENED, LIMIT)));

        assertThatThrownBy(
                        () ->
                                service.updateLimit(
                                        ADMIN_ID,
                                        CUSTOMER_ID,
                                        BigDecimal.valueOf(20_000_000L),
                                        BigDecimal.valueOf(40_000_000L),
                                        NOW))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.ACCOUNT_CONCURRENT_MODIFICATION.getMessage());

        verify(accountMapper, never())
                .updateLimit(
                        anyLong(), any(Status.class), any(BigDecimal.class), any(BigDecimal.class));
    }

    @Test
    void updateLimitUsesClientExpectedLimitForCompareAndSet() {
        AccountDTO current = account(Status.OPENED, LIMIT);
        AccountDTO updated = account(Status.OPENED, BigDecimal.valueOf(40_000_000L));
        when(accountMapper.selectByCustomerId(CUSTOMER_ID)).thenReturn(Optional.of(current));
        when(accountMapper.selectOwnUsedAndReservedAmount(ACCOUNT_ID)).thenReturn(BigDecimal.ZERO);
        when(accountMapper.updateLimit(
                        ACCOUNT_ID, Status.OPENED, LIMIT, BigDecimal.valueOf(40_000_000L)))
                .thenReturn(1);
        when(accountMapper.selectByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(updated));

        service.updateLimit(ADMIN_ID, CUSTOMER_ID, LIMIT, BigDecimal.valueOf(40_000_000L), NOW);

        verify(accountMapper)
                .updateLimit(ACCOUNT_ID, Status.OPENED, LIMIT, BigDecimal.valueOf(40_000_000L));
        assertAuditLog(
                "limitAmount=30000000",
                "limitAmount=40000000",
                AuditLogReasonCode.ACCOUNT_CHANGE_LIMIT_AMOUNT);
    }

    @Test
    void applyKeepsAccountAppliedWhenAutoApprovalConditionIsNotMet() {
        AccountDTO applied = account(Status.APPLIED, LIMIT);
        when(accountMapper.existsByCustomerId(CUSTOMER_ID)).thenReturn(false);
        when(accountMapper.insertApplication(any(AccountDTO.class))).thenReturn(1);
        when(accountMapper.selectByCustomerId(CUSTOMER_ID)).thenReturn(Optional.of(applied));

        AccountDTO result = service.apply(ADMIN_ID, account(Status.APPLIED, LIMIT), NOW, false);

        assertThat(result.getStatus()).isEqualTo(Status.APPLIED);
        verify(accountMapper, never()).reject(any(AccountDTO.class));
        verify(accountMapper, never()).approve(any(AccountDTO.class));
        assertAuditLog(null, "APPLIED", AuditLogReasonCode.ACCOUNT_APPLY);
    }

    @Test
    void reapplyWritesAuditLogWithStatusTransition() {
        AccountDTO rejected = account(Status.REJECTED, LIMIT);
        AccountDTO applied = account(Status.APPLIED, LIMIT);
        when(accountMapper.selectByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(rejected), Optional.of(applied));
        when(accountMapper.reapply(any(AccountDTO.class))).thenReturn(1);

        service.reapply(
                ADMIN_ID,
                ACCOUNT_ID,
                AccountReapplyRequestDTO.builder().limitAmount(LIMIT).build(),
                NOW);

        assertAuditLog("REJECTED", "APPLIED", AuditLogReasonCode.ACCOUNT_REAPPLY);
    }

    @Test
    void applyWithAutoApprovalRecordsPossibleBenefit() {
        AccountDTO applied = account(Status.APPLIED, LIMIT);
        AccountDTO opened = openedAccount();
        when(accountMapper.existsByCustomerId(CUSTOMER_ID)).thenReturn(false);
        when(accountMapper.insertApplication(any(AccountDTO.class))).thenReturn(1);
        when(accountMapper.selectByCustomerId(CUSTOMER_ID))
                .thenReturn(Optional.of(applied), Optional.of(opened));
        when(accountMapper.approve(any(AccountDTO.class))).thenReturn(1);

        service.apply(ADMIN_ID, account(Status.APPLIED, LIMIT), NOW, true);

        verify(accountLogService).recordBenefitChange(opened, null, NOW, "계좌 개설에 따른 세제혜택 가능");
        ArgumentCaptor<AuditLogDTO> auditLogCaptor = ArgumentCaptor.forClass(AuditLogDTO.class);
        verify(auditLogService, times(2)).log(auditLogCaptor.capture());
        assertThat(auditLogCaptor.getAllValues())
                .extracting(
                        AuditLogDTO::getBeforeValue,
                        AuditLogDTO::getAfterValue,
                        AuditLogDTO::getReasonCode)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                null, "APPLIED", AuditLogReasonCode.ACCOUNT_APPLY.name()),
                        org.assertj.core.groups.Tuple.tuple(
                                "APPLIED", "OPENED", AuditLogReasonCode.ACCOUNT_OPENED.name()));
    }

    @Test
    void approveUsesValidatedLimitAsOptimisticLockCondition() {
        AccountDTO applied = account(Status.APPLIED, BigDecimal.valueOf(40_000_000L));
        AccountDTO opened = openedAccount();
        when(accountMapper.selectByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(applied), Optional.of(opened));
        when(accountMapper.approve(any(AccountDTO.class))).thenReturn(1);

        service.approve(ADMIN_ID, ACCOUNT_ID, LIMIT, NOW);

        ArgumentCaptor<AccountDTO> captor = ArgumentCaptor.forClass(AccountDTO.class);
        verify(accountMapper).approve(captor.capture());
        assertThat(captor.getValue().getLimitAmount()).isEqualByComparingTo(LIMIT);

        ArgumentCaptor<AuditLogDTO> auditLogCaptor = ArgumentCaptor.forClass(AuditLogDTO.class);
        verify(auditLogService).log(auditLogCaptor.capture());
        assertThat(auditLogCaptor.getValue())
                .extracting(
                        AuditLogDTO::getAdminId,
                        AuditLogDTO::getTargetTable,
                        AuditLogDTO::getTargetPk,
                        AuditLogDTO::getBeforeValue,
                        AuditLogDTO::getAfterValue,
                        AuditLogDTO::getReasonCode)
                .containsExactly(
                        ADMIN_ID,
                        "ACCOUNT",
                        "10",
                        "APPLIED",
                        "OPENED",
                        AuditLogReasonCode.ACCOUNT_OPENED.name());
        verify(accountLogService).recordBenefitChange(opened, null, NOW, "계좌 개설에 따른 세제혜택 가능");
    }

    @Test
    void approveReportsConflictWhenValidatedLimitChangedBeforeUpdate() {
        when(accountMapper.selectByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(account(Status.APPLIED, LIMIT)));
        when(accountMapper.approve(any(AccountDTO.class))).thenReturn(0);

        assertThatThrownBy(() -> service.approve(ADMIN_ID, ACCOUNT_ID, LIMIT, NOW))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.ACCOUNT_CONCURRENT_MODIFICATION.getMessage());
    }

    @Test
    void approveReportsAccountNumberGenerationFailureAfterRetryLimit() {
        when(accountMapper.selectByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(account(Status.APPLIED, LIMIT)));
        when(accountMapper.approve(any(AccountDTO.class)))
                .thenThrow(new DuplicateKeyException("duplicate account number"));

        assertThatThrownBy(() -> service.approve(ADMIN_ID, ACCOUNT_ID, LIMIT, NOW))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception ->
                                assertThat(exception.getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_NUMBER_GENERATION_FAILED));

        verify(accountMapper, times(5)).approve(any(AccountDTO.class));
    }

    @Test
    void approveReportsStatusPostconditionFailureWithDedicatedError() {
        AccountDTO applied = account(Status.APPLIED, LIMIT);
        when(accountMapper.selectByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(applied), Optional.of(applied));
        when(accountMapper.approve(any(AccountDTO.class))).thenReturn(1);

        assertThatThrownBy(() -> service.approve(ADMIN_ID, ACCOUNT_ID, LIMIT, NOW))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception ->
                                assertThat(exception.getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_STATE_UPDATE_FAILED));
    }

    @Test
    void approveReportsBenefitPostconditionFailureWithDedicatedError() {
        AccountDTO applied = account(Status.APPLIED, LIMIT);
        AccountDTO openedWithoutBenefit = account(Status.OPENED, LIMIT);
        when(accountMapper.selectByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(applied), Optional.of(openedWithoutBenefit));
        when(accountMapper.approve(any(AccountDTO.class))).thenReturn(1);

        assertThatThrownBy(() -> service.approve(ADMIN_ID, ACCOUNT_ID, LIMIT, NOW))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception ->
                                assertThat(exception.getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_BENEFIT_UPDATE_FAILED));

        verify(accountLogService)
                .recordStatusChange(openedWithoutBenefit, Status.APPLIED, NOW, "사용자 계좌 개설");
    }

    @Test
    void overrideRecordsPossibleBenefit() {
        AccountDTO rejected = account(Status.REJECTED, LIMIT);
        AccountDTO opened = openedAccount();
        when(accountMapper.selectByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(rejected), Optional.of(opened));
        when(accountMapper.overrideToOpened(any(AccountDTO.class))).thenReturn(1);

        service.override(ADMIN_ID, ACCOUNT_ID, "관리자 오버라이드 승인", NOW);

        verify(accountLogService).recordBenefitChange(opened, null, NOW, "계좌 개설에 따른 세제혜택 가능");
        assertAuditLog("REJECTED", "OPENED", AuditLogReasonCode.ACCOUNT_OVERRIDE_OPENED);
    }

    @Test
    void rejectWritesAuditLogWithStatusTransition() {
        AccountDTO applied = account(Status.APPLIED, LIMIT);
        AccountDTO rejected = account(Status.REJECTED, LIMIT);
        when(accountMapper.selectByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(applied), Optional.of(rejected));
        when(accountMapper.reject(applied)).thenReturn(1);

        service.reject(ADMIN_ID, ACCOUNT_ID, "심사 반려", NOW);

        assertAuditLog("APPLIED", "REJECTED", AuditLogReasonCode.ACCOUNT_REJECTED);
    }

    @Test
    void overrideWritesAuditLogWithStatusTransition() {
        AccountDTO rejected = account(Status.REJECTED, LIMIT);
        AccountDTO opened = openedAccount();
        when(accountMapper.selectByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(rejected), Optional.of(opened));
        when(accountMapper.overrideToOpened(any(AccountDTO.class))).thenReturn(1);

        service.override(ADMIN_ID, ACCOUNT_ID, "재심사 승인", NOW);

        assertAuditLog("REJECTED", "OPENED", AuditLogReasonCode.ACCOUNT_OVERRIDE_OPENED);
    }

    @Test
    void updateAmountPassesProvisionalDeltaToAtomicBalanceUpdate() {
        AccountDTO increase =
                AccountDTO.builder()
                        .accountId(ACCOUNT_ID)
                        .amount(BigDecimal.valueOf(267_300L))
                        .build();
        when(accountMapper.selectByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(account(Status.OPENED, LIMIT)));
        when(accountMapper.updateProvisionalAmount(increase)).thenReturn(1);

        service.updateAmount(increase);

        ArgumentCaptor<AccountDTO> captor = ArgumentCaptor.forClass(AccountDTO.class);
        verify(accountMapper).updateProvisionalAmount(captor.capture());
        assertThat(captor.getValue().getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("267300");
    }

    @Test
    void updateAmountThrowsWhenNoAccountBalanceIsUpdated() {
        AccountDTO increase =
                AccountDTO.builder().accountId(ACCOUNT_ID).amount(BigDecimal.ONE).build();
        when(accountMapper.selectByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(account(Status.OPENED, LIMIT)));
        when(accountMapper.updateProvisionalAmount(increase)).thenReturn(0);

        assertThatThrownBy(() -> service.updateAmount(increase))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.ACCOUNT_AMOUNT_UPDATE_FAILED.getMessage());
    }

    private AccountDTO account(Status status, BigDecimal limit) {
        return AccountDTO.builder()
                .accountId(ACCOUNT_ID)
                .customerId(CUSTOMER_ID)
                .status(status)
                .limitAmount(limit)
                .build();
    }

    private AccountDTO openedAccount() {
        AccountDTO account = account(Status.OPENED, LIMIT);
        account.setBenefit(BenefitType.POSSIBLE);
        return account;
    }

    private void assertAuditLog(
            String beforeValue, String afterValue, AuditLogReasonCode reasonCode) {
        ArgumentCaptor<AuditLogDTO> captor = ArgumentCaptor.forClass(AuditLogDTO.class);
        verify(auditLogService).log(captor.capture());
        AuditLogDTO auditLog = captor.getValue();
        assertThat(auditLog.getAdminId()).isEqualTo(ADMIN_ID);
        assertThat(auditLog.getTargetTable()).isEqualTo("ACCOUNT");
        assertThat(auditLog.getTargetPk()).isEqualTo(String.valueOf(ACCOUNT_ID));
        assertThat(auditLog.getBeforeValue()).isEqualTo(beforeValue);
        assertThat(auditLog.getAfterValue()).isEqualTo(afterValue);
        assertThat(auditLog.getReasonCode()).isEqualTo(reasonCode.name());
    }
}
