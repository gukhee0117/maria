package com.app.maria.domain.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.customer.dto.request.CustomerWithdrawalRequestDTO;
import com.app.maria.domain.withdrawal.dto.WithdrawalAllocationDTO;
import com.app.maria.domain.withdrawal.dto.WithdrawalResultDTO;
import com.app.maria.domain.withdrawal.dto.request.WithdrawalRequestDTO;
import com.app.maria.domain.withdrawal.dto.response.WithdrawalDetailResponseDTO;
import com.app.maria.domain.withdrawal.dto.response.WithdrawalListResponseDTO;
import com.app.maria.domain.withdrawal.service.WithdrawalService;
import com.app.maria.domain.withdrawal.type.WithdrawalStatus;
import com.app.maria.global.client.generalaccount.GeneralAccountClient;
import com.app.maria.global.client.generalaccount.dto.response.GeneralAccountResponseDTO;
import com.app.maria.global.client.generalaccount.type.GeneralAccountStatus;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CustomerWithdrawalServiceImplTest {

    private static final Long CUSTOMER_ID = 1L;
    private static final Long ACCOUNT_ID = 10L;
    private static final String CI_HASH = "ci-hash-test";

    @Mock private AccountMapper accountMapper;
    @Mock private WithdrawalService withdrawalService;
    @Mock private GeneralAccountClient generalAccountClient;

    @InjectMocks private CustomerWithdrawalServiceImpl service;

    // ── stubs ──────────────────────────────────────────────────────────────

    private void stubAccount() {
        AccountDTO account = AccountDTO.builder().accountId(ACCOUNT_ID).build();
        when(accountMapper.selectByCustomerId(CUSTOMER_ID)).thenReturn(Optional.of(account));
    }

    private void stubCiHash() {
        when(accountMapper.selectCiHashByCustomerId(CUSTOMER_ID)).thenReturn(Optional.of(CI_HASH));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private CustomerWithdrawalRequestDTO request(
            BigDecimal amount, Long generalAccountId, boolean earlyAgreed) {
        CustomerWithdrawalRequestDTO dto = new CustomerWithdrawalRequestDTO();
        ReflectionTestUtils.setField(dto, "requestedAmount", amount);
        ReflectionTestUtils.setField(dto, "destinationGeneralAccountId", generalAccountId);
        ReflectionTestUtils.setField(dto, "earlyWithdrawalAgreed", earlyAgreed);
        return dto;
    }

    // ── getDestinationAccounts ─────────────────────────────────────────────

    @Test
    @DisplayName("CI 해시로 조회한 활성 계좌 목록을 반환한다")
    void getDestinationAccounts_returnsAccountsFromClient() {
        stubCiHash();
        GeneralAccountResponseDTO account =
                GeneralAccountResponseDTO.builder()
                        .generalAccountId(20L)
                        .accountNo("1234567890")
                        .status(GeneralAccountStatus.ACTIVE)
                        .build();
        when(generalAccountClient.findByCiHash(CI_HASH)).thenReturn(List.of(account));

        List<GeneralAccountResponseDTO> result = service.getDestinationAccounts(CUSTOMER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getGeneralAccountId()).isEqualTo(20L);
        verify(generalAccountClient).findByCiHash(CI_HASH);
    }

    @Test
    @DisplayName("CI 해시 조회 실패 시 ACCOUNT_NOT_FOUND 예외를 던진다")
    void getDestinationAccounts_noCiHash_throwsAccountNotFound() {
        when(accountMapper.selectCiHashByCustomerId(CUSTOMER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDestinationAccounts(CUSTOMER_ID))
                .isInstanceOf(AppException.class)
                .satisfies(
                        ex ->
                                assertThat(((AppException) ex).getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_NOT_FOUND));

        verify(generalAccountClient, never()).findByCiHash(any());
    }

    // ── withdraw ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("고객 ID로 계좌를 조회해 WithdrawalRequestDTO를 구성하고 WithdrawalService에 위임한다")
    void withdraw_delegatesWithCorrectRequest() {
        stubAccount();
        WithdrawalResultDTO expected =
                WithdrawalResultDTO.builder()
                        .withdrawalId(5L)
                        .allocations(List.of(WithdrawalAllocationDTO.builder().build()))
                        .build();
        when(withdrawalService.withdraw(any(WithdrawalRequestDTO.class))).thenReturn(expected);

        WithdrawalResultDTO result =
                service.withdraw(
                        CUSTOMER_ID, request(new BigDecimal("100000"), 20L, false));

        assertThat(result.getWithdrawalId()).isEqualTo(5L);

        ArgumentCaptor<WithdrawalRequestDTO> captor =
                ArgumentCaptor.forClass(WithdrawalRequestDTO.class);
        verify(withdrawalService).withdraw(captor.capture());
        WithdrawalRequestDTO captured = captor.getValue();
        assertThat(captured.getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(captured.getRequestedAmount()).isEqualByComparingTo("100000");
        assertThat(captured.getDestinationGeneralAccountId()).isEqualTo(20L);
        assertThat(captured.isEarlyWithdrawalAgreed()).isFalse();
    }

    @Test
    @DisplayName("earlyWithdrawalAgreed=true이면 WithdrawalRequestDTO에 그대로 전달된다")
    void withdraw_propagatesEarlyWithdrawalAgreed() {
        stubAccount();
        when(withdrawalService.withdraw(any())).thenReturn(WithdrawalResultDTO.builder().build());

        service.withdraw(CUSTOMER_ID, request(new BigDecimal("500000"), 20L, true));

        ArgumentCaptor<WithdrawalRequestDTO> captor =
                ArgumentCaptor.forClass(WithdrawalRequestDTO.class);
        verify(withdrawalService).withdraw(captor.capture());
        assertThat(captor.getValue().isEarlyWithdrawalAgreed()).isTrue();
    }

    @Test
    @DisplayName("계좌가 없으면 ACCOUNT_NOT_FOUND 예외를 던진다")
    void withdraw_noAccount_throwsAccountNotFound() {
        when(accountMapper.selectByCustomerId(CUSTOMER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                service.withdraw(
                                        CUSTOMER_ID,
                                        request(new BigDecimal("100000"), 20L, false)))
                .isInstanceOf(AppException.class)
                .satisfies(
                        ex ->
                                assertThat(((AppException) ex).getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_NOT_FOUND));

        verify(withdrawalService, never()).withdraw(any());
    }

    // ── getWithdrawals ─────────────────────────────────────────────────────

    @Test
    @DisplayName("계좌 ID로 인출 이력을 조회해 반환한다")
    void getWithdrawals_delegatesToWithdrawalService() {
        stubAccount();
        WithdrawalListResponseDTO item =
                WithdrawalListResponseDTO.builder()
                        .withdrawalId(7L)
                        .status(WithdrawalStatus.COMPLETED)
                        .build();
        when(withdrawalService.getWithdrawalsByAccountId(ACCOUNT_ID)).thenReturn(List.of(item));

        List<WithdrawalListResponseDTO> result = service.getWithdrawals(CUSTOMER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getWithdrawalId()).isEqualTo(7L);
        verify(withdrawalService).getWithdrawalsByAccountId(ACCOUNT_ID);
    }

    @Test
    @DisplayName("계좌가 없으면 ACCOUNT_NOT_FOUND 예외를 던진다")
    void getWithdrawals_noAccount_throwsAccountNotFound() {
        when(accountMapper.selectByCustomerId(CUSTOMER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getWithdrawals(CUSTOMER_ID))
                .isInstanceOf(AppException.class)
                .satisfies(
                        ex ->
                                assertThat(((AppException) ex).getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_NOT_FOUND));

        verify(withdrawalService, never()).getWithdrawalsByAccountId(any());
    }

    // ── getWithdrawal ──────────────────────────────────────────────────────

    @Test
    @DisplayName("인출 ID와 계좌 ID를 함께 조회해 소유권을 검증한다")
    void getWithdrawal_delegatesToWithdrawalServiceWithOwnershipCheck() {
        stubAccount();
        WithdrawalDetailResponseDTO detail =
                WithdrawalDetailResponseDTO.builder()
                        .withdrawalId(7L)
                        .earlyWithdrawal(false)
                        .allocations(List.of())
                        .build();
        when(withdrawalService.getWithdrawalByIdAndAccountId(7L, ACCOUNT_ID)).thenReturn(detail);

        WithdrawalDetailResponseDTO result = service.getWithdrawal(CUSTOMER_ID, 7L);

        assertThat(result.getWithdrawalId()).isEqualTo(7L);
        verify(withdrawalService).getWithdrawalByIdAndAccountId(7L, ACCOUNT_ID);
    }

    @Test
    @DisplayName("타인의 인출 ID 조회 시 서비스가 WITHDRAWAL_NOT_FOUND를 던지면 그대로 전파된다")
    void getWithdrawal_otherCustomerWithdrawal_propagatesNotFound() {
        stubAccount();
        when(withdrawalService.getWithdrawalByIdAndAccountId(99L, ACCOUNT_ID))
                .thenThrow(new AppException(ErrorType.WITHDRAWAL_NOT_FOUND));

        assertThatThrownBy(() -> service.getWithdrawal(CUSTOMER_ID, 99L))
                .isInstanceOf(AppException.class)
                .satisfies(
                        ex ->
                                assertThat(((AppException) ex).getErrorType())
                                        .isEqualTo(ErrorType.WITHDRAWAL_NOT_FOUND));
    }

    @Test
    @DisplayName("계좌가 없으면 ACCOUNT_NOT_FOUND 예외를 던진다")
    void getWithdrawal_noAccount_throwsAccountNotFound() {
        when(accountMapper.selectByCustomerId(CUSTOMER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getWithdrawal(CUSTOMER_ID, 7L))
                .isInstanceOf(AppException.class)
                .satisfies(
                        ex ->
                                assertThat(((AppException) ex).getErrorType())
                                        .isEqualTo(ErrorType.ACCOUNT_NOT_FOUND));

        verify(withdrawalService, never()).getWithdrawalByIdAndAccountId(any(), any());
    }
}
