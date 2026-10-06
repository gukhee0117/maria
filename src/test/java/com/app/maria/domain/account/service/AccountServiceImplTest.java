package com.app.maria.domain.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.dto.AccountLimitUsageDTO;
import com.app.maria.domain.account.dto.AccountSearchDTO;
import com.app.maria.domain.account.dto.request.AccountLimitUpdateRequestDTO;
import com.app.maria.domain.account.dto.request.AccountReapplyRequestDTO;
import com.app.maria.domain.account.dto.request.AccountRequestDTO;
import com.app.maria.domain.account.dto.request.AccountSearchRequestDTO;
import com.app.maria.domain.account.dto.response.AccountLimitUsageResponseDTO;
import com.app.maria.domain.account.dto.response.AccountResponseDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.account.provider.MydataProvider;
import com.app.maria.domain.account.type.Status;
import com.app.maria.global.audit.provider.AuditActorProvider;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountServiceImplTest {
    private static final Long CUSTOMER_ID = 1L;
    private static final Long ACCOUNT_ID = 10L;
    private static final Long ADMIN_ID = 99L;
    private static final BigDecimal LIMIT = BigDecimal.valueOf(30_000_000L);
    private static final BigDecimal CHANGED_LIMIT = BigDecimal.valueOf(40_000_000L);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 2, 10, 30);

    @Mock private AccountMapper accountMapper;
    @Mock private MydataProvider mydataProvider;
    @Mock private AccountLogService accountLogService;
    @Mock private BusinessClockService businessClockService;
    @Mock private AccountTransactionalService accountTransactionalService;
    @Mock private AccountMydataSyncService accountMydataSyncService;
    @Mock private AuditActorProvider auditActorProvider;

    @InjectMocks private AccountServiceImpl accountService;

    @BeforeEach
    void setUp() {
        when(accountMapper.existsCustomerById(CUSTOMER_ID)).thenReturn(true);
        when(accountMapper.selectCiHashByCustomerId(CUSTOMER_ID))
                .thenReturn(Optional.of("ci-hash"));
        when(mydataProvider.getExternalConfiguredLimit("ci-hash")).thenReturn(BigDecimal.ZERO);
        when(businessClockService.now()).thenReturn(NOW);
        when(auditActorProvider.getCurrentAdminId()).thenReturn(ADMIN_ID);
    }

    @Test
    void updateLimitDoesNotSyncMydataForAppliedAccount() {
        when(accountTransactionalService.updateLimit(
                        ADMIN_ID, CUSTOMER_ID, LIMIT, CHANGED_LIMIT, NOW))
                .thenReturn(account(Status.APPLIED, CHANGED_LIMIT));

        AccountResponseDTO result =
                accountService.updateAccountLimit(limitUpdateRequest(LIMIT, CHANGED_LIMIT));

        assertThat(result.getStatus()).isEqualTo(Status.APPLIED);
        verify(accountMydataSyncService, never()).updateLimit(any());
    }

    @Test
    void updateLimitSyncsOnlyLimitForOpenedAccount() {
        AccountDTO updated = account(Status.OPENED, CHANGED_LIMIT);
        when(accountTransactionalService.updateLimit(
                        ADMIN_ID, CUSTOMER_ID, LIMIT, CHANGED_LIMIT, NOW))
                .thenReturn(updated);

        accountService.updateAccountLimit(limitUpdateRequest(LIMIT, CHANGED_LIMIT));

        verify(accountMydataSyncService).updateLimit(updated);
        verify(accountMydataSyncService, never()).create(any());
    }

    @Test
    void updateLimitRejectsAmountThatExceedsMydataAvailableLimit() {
        when(mydataProvider.getExternalConfiguredLimit("ci-hash"))
                .thenReturn(BigDecimal.valueOf(20_000_000L));

        assertThatThrownBy(
                        () ->
                                accountService.updateAccountLimit(
                                        limitUpdateRequest(LIMIT, CHANGED_LIMIT)))
                .isInstanceOf(AppException.class)
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception ->
                                assertThat(exception.getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_LIMIT_EXCEEDS_AVAILABLE));

        verify(accountTransactionalService, never()).updateLimit(any(), any(), any(), any(), any());
    }

    @Test
    void updateLimitRejectsWhenNoLimitIsAvailable() {
        when(mydataProvider.getExternalConfiguredLimit("ci-hash"))
                .thenReturn(BigDecimal.valueOf(50_000_000L));

        assertThatThrownBy(
                        () ->
                                accountService.updateAccountLimit(
                                        limitUpdateRequest(LIMIT, CHANGED_LIMIT)))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception ->
                                assertThat(exception.getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_NO_LIMIT_AVAILABLE));

        verify(accountTransactionalService, never()).updateLimit(any(), any(), any(), any(), any());
    }

    @Test
    void getAvailableLimitDistinguishesMissingCustomer() {
        when(accountMapper.existsCustomerById(CUSTOMER_ID)).thenReturn(false);

        assertThatThrownBy(() -> accountService.getAvailableLimit(CUSTOMER_ID))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception ->
                                assertThat(exception.getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_CUSTOMER_NOT_FOUND));
    }

    @Test
    void getAvailableLimitDistinguishesMissingCustomerIdentity() {
        when(accountMapper.selectCiHashByCustomerId(CUSTOMER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getAvailableLimit(CUSTOMER_ID))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception ->
                                assertThat(exception.getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_CUSTOMER_IDENTITY_NOT_FOUND));
    }

    @Test
    void updateLimitRejectsMissingLimitWithDedicatedError() {
        assertThatThrownBy(() -> accountService.updateAccountLimit(limitUpdateRequest(LIMIT, null)))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception ->
                                assertThat(exception.getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_LIMIT_REQUIRED));
    }

    @Test
    void updateLimitRejectsAmountBelowMinimumWithDedicatedError() {
        assertThatThrownBy(
                        () ->
                                accountService.updateAccountLimit(
                                        limitUpdateRequest(LIMIT, BigDecimal.ZERO)))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception ->
                                assertThat(exception.getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_LIMIT_BELOW_MINIMUM));
    }

    @Test
    void updateLimitRejectsAmountAboveMaximumWithDedicatedError() {
        BigDecimal aboveMaximum = BigDecimal.valueOf(50_000_001L);

        assertThatThrownBy(
                        () ->
                                accountService.updateAccountLimit(
                                        limitUpdateRequest(LIMIT, aboveMaximum)))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception ->
                                assertThat(exception.getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_LIMIT_ABOVE_MAXIMUM));
    }

    @Test
    void updateLimitRejectsFractionalWonWithDedicatedError() {
        assertThatThrownBy(
                        () ->
                                accountService.updateAccountLimit(
                                        limitUpdateRequest(LIMIT, new BigDecimal("1000.50"))))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception ->
                                assertThat(exception.getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_LIMIT_NOT_WHOLE_WON));
    }

    @Test
    void reapplyRejectsClosedApplicationPeriodWithDedicatedError() {
        LocalDateTime outsidePeriod = LocalDateTime.of(2027, 1, 1, 10, 0);
        when(accountMapper.selectByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(account(Status.REJECTED, LIMIT)));
        when(businessClockService.now()).thenReturn(outsidePeriod);

        assertThatThrownBy(
                        () ->
                                accountService.reapplyAccountByAccountId(
                                        ACCOUNT_ID, AccountReapplyRequestDTO.builder().build()))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception ->
                                assertThat(exception.getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_APPLICATION_PERIOD_CLOSED));
    }

    @Test
    void applyUsesSingleBusinessClockSnapshotAndCreatesMydataForOpenedAccount() {
        AccountDTO opened = account(Status.OPENED, LIMIT);
        when(accountTransactionalService.apply(
                        eq(ADMIN_ID), any(AccountDTO.class), eq(NOW), eq(true)))
                .thenReturn(opened);

        accountService.applyAccount(request(LIMIT));

        verify(businessClockService).now();
        verify(accountMydataSyncService).create(opened);
    }

    @Test
    void applyKeepsAccountAppliedWhenExternalLimitDoesNotMatch() {
        AccountDTO applied = account(Status.APPLIED, LIMIT);
        when(mydataProvider.getExternalConfiguredLimit("ci-hash"))
                .thenReturn(BigDecimal.valueOf(25_000_000L));
        when(accountTransactionalService.apply(
                        eq(ADMIN_ID), any(AccountDTO.class), eq(NOW), eq(false)))
                .thenReturn(applied);

        AccountResponseDTO result = accountService.applyAccount(request(LIMIT));

        assertThat(result.getStatus()).isEqualTo(Status.APPLIED);
        verify(accountTransactionalService)
                .apply(eq(ADMIN_ID), any(AccountDTO.class), eq(NOW), eq(false));
        verify(accountMydataSyncService, never()).create(any());
    }

    @Test
    void applyKeepsAccountAppliedOutsideApplicationPeriod() {
        LocalDateTime outsidePeriod = LocalDateTime.of(2027, 1, 1, 10, 0);
        AccountDTO applied = account(Status.APPLIED, LIMIT);
        when(businessClockService.now()).thenReturn(outsidePeriod);
        when(accountTransactionalService.apply(
                        eq(ADMIN_ID), any(AccountDTO.class), eq(outsidePeriod), eq(false)))
                .thenReturn(applied);

        AccountResponseDTO result = accountService.applyAccount(request(LIMIT));

        assertThat(result.getStatus()).isEqualTo(Status.APPLIED);
        verify(accountMydataSyncService, never()).create(any());
    }

    @Test
    void approvePassesValidatedLimitAndCreatesMydataAccount() {
        AccountDTO applied = account(Status.APPLIED, LIMIT);
        AccountDTO opened = account(Status.OPENED, LIMIT);
        when(accountMapper.selectByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(applied));
        when(accountTransactionalService.approve(ADMIN_ID, ACCOUNT_ID, LIMIT, NOW))
                .thenReturn(opened);

        accountService.approveAccount(ACCOUNT_ID);

        verify(accountTransactionalService).approve(ADMIN_ID, ACCOUNT_ID, LIMIT, NOW);
        verify(accountMydataSyncService).create(opened);
    }

    @Test
    void approveAllowsPendingAccountOutsideApplicationPeriod() {
        LocalDateTime afterApplicationPeriod = LocalDateTime.of(2027, 1, 1, 10, 0);
        AccountDTO applied = account(Status.APPLIED, LIMIT);
        AccountDTO opened = account(Status.OPENED, LIMIT);
        when(businessClockService.now()).thenReturn(afterApplicationPeriod);
        when(accountMapper.selectByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(applied));
        when(accountTransactionalService.approve(
                        ADMIN_ID, ACCOUNT_ID, LIMIT, afterApplicationPeriod))
                .thenReturn(opened);

        accountService.approveAccount(ACCOUNT_ID);

        verify(accountTransactionalService)
                .approve(ADMIN_ID, ACCOUNT_ID, LIMIT, afterApplicationPeriod);
    }

    @Test
    @DisplayName("검색 조건을 VO로 변환해 Mapper를 호출하고, 결과를 Response DTO로 감싸 반환한다")
    void searchAccountsConvertsRequestToVoAndWrapsMapperResultAsResponseDto() {
        AccountLimitUsageDTO vo = accountLimitUsage(Status.OPENED);
        AccountSearchRequestDTO request =
                AccountSearchRequestDTO.builder().accountNo("123").customerName("김").build();
        when(accountMapper.searchAccounts(any(AccountSearchDTO.class))).thenReturn(List.of(vo));

        List<AccountLimitUsageResponseDTO> result = accountService.searchAccounts(request);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAccountId()).isEqualTo(vo.getAccountId());
        assertThat(result.get(0).getAccountNo()).isEqualTo(vo.getAccountNo());

        ArgumentCaptor<AccountSearchDTO> captor = ArgumentCaptor.forClass(AccountSearchDTO.class);
        verify(accountMapper).searchAccounts(captor.capture());
        assertThat(captor.getValue().getAccountNo()).isEqualTo("123");
        assertThat(captor.getValue().getCustomerName()).isEqualTo("김");
    }

    @Test
    @DisplayName("한도 사용률 조회 결과를 Response DTO로 감싸 반환한다")
    void selectAccountLimitUsageWrapsMapperResultAsResponseDto() {
        AccountLimitUsageDTO vo = accountLimitUsage(Status.OPENED);
        when(accountMapper.selectAccountLimitUsage()).thenReturn(List.of(vo));

        List<AccountLimitUsageResponseDTO> result = accountService.selectAccountLimitUsage();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(Status.OPENED);
        assertThat(result.get(0).getUsedAmount()).isEqualByComparingTo(vo.getUsedAmount());
    }

    @Test
    @DisplayName("심사대기 계좌 조회 결과를 Response DTO로 감싸 반환한다")
    void getAppliedAccountsWrapsMapperResultAsResponseDto() {
        AccountLimitUsageDTO vo = accountLimitUsage(Status.APPLIED);
        when(accountMapper.selectAppliedAccounts()).thenReturn(List.of(vo));

        List<AccountLimitUsageResponseDTO> result = accountService.getAppliedAccounts();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(Status.APPLIED);
    }

    private AccountLimitUsageDTO accountLimitUsage(Status status) {
        return AccountLimitUsageDTO.builder()
                .accountId(ACCOUNT_ID)
                .accountNo("1234567890")
                .customerName("김리아")
                .status(status)
                .limitAmount(LIMIT)
                .usedAmount(BigDecimal.ZERO)
                .build();
    }

    private AccountRequestDTO request(BigDecimal limit) {
        return AccountRequestDTO.builder().customerId(CUSTOMER_ID).limitAmount(limit).build();
    }

    private AccountLimitUpdateRequestDTO limitUpdateRequest(
            BigDecimal expectedCurrentLimit, BigDecimal limitAmount) {
        return AccountLimitUpdateRequestDTO.builder()
                .customerId(CUSTOMER_ID)
                .expectedCurrentLimit(expectedCurrentLimit)
                .limitAmount(limitAmount)
                .build();
    }

    private AccountDTO account(Status status, BigDecimal limit) {
        return AccountDTO.builder()
                .accountId(ACCOUNT_ID)
                .customerId(CUSTOMER_ID)
                .status(status)
                .limitAmount(limit)
                .build();
    }
}
