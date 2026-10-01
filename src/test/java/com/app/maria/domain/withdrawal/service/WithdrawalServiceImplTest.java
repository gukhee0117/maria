package com.app.maria.domain.withdrawal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.withdrawal.dto.LeftAmountDTO;
import com.app.maria.domain.withdrawal.dto.WithdrawalAllocationHistoryDTO;
import com.app.maria.domain.withdrawal.dto.WithdrawalFailureContext;
import com.app.maria.domain.withdrawal.dto.WithdrawalHistoryDTO;
import com.app.maria.domain.withdrawal.dto.WithdrawalResultDTO;
import com.app.maria.domain.withdrawal.dto.request.WithdrawalRequestDTO;
import com.app.maria.domain.withdrawal.dto.response.WithdrawalDetailResponseDTO;
import com.app.maria.domain.withdrawal.dto.response.WithdrawalListResponseDTO;
import com.app.maria.domain.withdrawal.mapper.WithdrawalMapper;
import com.app.maria.domain.withdrawal.type.WithdrawalStatus;
import com.app.maria.domain.withdrawal.type.WithdrawalType;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WithdrawalServiceImplTest {
    private static final Long ACCOUNT_ID = 1L;
    private static final Long WITHDRAWAL_ID = 30L;
    private static final LocalDateTime PROCESSED_AT = LocalDateTime.of(2026, 8, 12, 10, 0);

    @Mock private WithdrawalMapper withdrawalMapper;
    @Mock private BusinessClockService businessClockService;
    @Mock private WithdrawalProcessor withdrawalProcessor;
    @Mock private WithdrawalFailureService withdrawalFailureService;
    @InjectMocks private WithdrawalServiceImpl withdrawalService;

    @Test
    void listConvertsMapperResultsWithoutChangingOrderOrAmounts() {
        when(withdrawalMapper.selectWithdrawalHistories(WithdrawalStatus.COMPLETED))
                .thenReturn(List.of(history(2L, "900"), history(1L, "800")));

        List<WithdrawalListResponseDTO> result =
                withdrawalService.getWithdrawals(WithdrawalStatus.COMPLETED);

        assertThat(result)
                .extracting(WithdrawalListResponseDTO::getWithdrawalId)
                .containsExactly(2L, 1L);
        assertThat(result.get(0).getImmaturePrincipalAmount()).isEqualByComparingTo("400");
        assertThat(result.get(0).getAllocationCount()).isEqualTo(4);
        assertThat(result.get(0).getNormalAllocationCount()).isEqualTo(3);
        assertThat(result.get(0).getEarlyAllocationCount()).isEqualTo(1);
        verify(withdrawalMapper).selectWithdrawalHistories(WithdrawalStatus.COMPLETED);
    }

    @Test
    void accountHistoryConvertsEveryStatusWithoutDroppingFailedWithdrawals() {
        WithdrawalHistoryDTO completed = history(2L, "900");
        WithdrawalHistoryDTO failed = history(1L, "800");
        failed.setStatus(WithdrawalStatus.FAILED);
        when(withdrawalMapper.selectWithdrawalHistoriesByAccountId(1L))
                .thenReturn(List.of(completed, failed));

        List<WithdrawalListResponseDTO> result = withdrawalService.getWithdrawalsByAccountId(1L);

        assertThat(result)
                .extracting(WithdrawalListResponseDTO::getStatus)
                .containsExactly(WithdrawalStatus.COMPLETED, WithdrawalStatus.FAILED);
        verify(withdrawalMapper).selectWithdrawalHistoriesByAccountId(1L);
    }

    @Test
    void detailIncludesAllocationMaturityAndEarlyWithdrawalResult() {
        WithdrawalHistoryDTO history = history(1L, "800");
        LocalDateTime finalAt = LocalDateTime.of(2026, 1, 1, 9, 0);
        WithdrawalAllocationHistoryDTO allocation =
                WithdrawalAllocationHistoryDTO.builder()
                        .allocationId(3L)
                        .leftAmountId(102L)
                        .exchangeId(2L)
                        .allocatedAmount(new BigDecimal("400"))
                        .withdrawalAt(PROCESSED_AT)
                        .type(WithdrawalType.IMMATURE_PRINCIPAL_INCLUDED)
                        .finalAt(finalAt)
                        .productName("Apple")
                        .ticker("AAPL")
                        .build();
        when(withdrawalMapper.selectWithdrawalHistoryById(1L)).thenReturn(Optional.of(history));
        when(withdrawalMapper.selectAllocationHistoriesByWithdrawalId(1L))
                .thenReturn(List.of(allocation));

        WithdrawalDetailResponseDTO result = withdrawalService.getWithdrawal(1L);

        assertThat(result.isEarlyWithdrawal()).isTrue();
        assertThat(result.getAllocationCount()).isEqualTo(4);
        assertThat(result.getNormalAllocationCount()).isEqualTo(3);
        assertThat(result.getEarlyAllocationCount()).isEqualTo(1);
        assertThat(result.getAllocations())
                .singleElement()
                .satisfies(
                        item -> {
                            assertThat(item.getAllocatedAmount()).isEqualByComparingTo("400");
                            assertThat(item.getFinalAt()).isEqualTo(finalAt);
                            assertThat(item.getMaturityAt()).isEqualTo(finalAt.plusYears(1));
                            assertThat(item.getProductName()).isEqualTo("Apple");
                            assertThat(item.getTicker()).isEqualTo("AAPL");
                        });
    }

    @Test
    void missingWithdrawalThrowsNotFoundWithoutAllocationQuery() {
        when(withdrawalMapper.selectWithdrawalHistoryById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> withdrawalService.getWithdrawal(99L))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.WITHDRAWAL_NOT_FOUND.getMessage())
                .hasMessage("인출 내역을 찾을 수 없습니다.");

        verify(withdrawalMapper, never()).selectAllocationHistoriesByWithdrawalId(99L);
    }

    @Test
    void principalOneSecondBeforeMaturityIsImmature() {
        when(withdrawalMapper.selectAvailableLeftAmountsByAccountId(ACCOUNT_ID))
                .thenReturn(
                        List.of(leftAmount(10L, "300", PROCESSED_AT.minusYears(1).plusSeconds(1))));
        when(businessClockService.now()).thenReturn(PROCESSED_AT);

        assertThat(withdrawalService.hasImmaturePrincipal(ACCOUNT_ID)).isTrue();
    }

    @Test
    void principalExactlyAtMaturityIsNotImmature() {
        when(withdrawalMapper.selectAvailableLeftAmountsByAccountId(ACCOUNT_ID))
                .thenReturn(List.of(leftAmount(10L, "300", PROCESSED_AT.minusYears(1))));
        when(businessClockService.now()).thenReturn(PROCESSED_AT);

        assertThat(withdrawalService.hasImmaturePrincipal(ACCOUNT_ID)).isFalse();
    }

    @Test
    void anyImmaturePrincipalMakesClosureConsentNecessary() {
        when(withdrawalMapper.selectAvailableLeftAmountsByAccountId(ACCOUNT_ID))
                .thenReturn(
                        List.of(
                                leftAmount(10L, "300", PROCESSED_AT.minusYears(2)),
                                leftAmount(11L, "200", PROCESSED_AT.minusMonths(6))));
        when(businessClockService.now()).thenReturn(PROCESSED_AT);

        assertThat(withdrawalService.hasImmaturePrincipal(ACCOUNT_ID)).isTrue();
    }

    @Test
    void immaturePrincipalAmountSumsOnlyLotsBeforeMaturity() {
        when(withdrawalMapper.selectAvailableLeftAmountsByAccountId(ACCOUNT_ID))
                .thenReturn(
                        List.of(
                                leftAmount(10L, "300", PROCESSED_AT.minusYears(2)),
                                leftAmount(11L, "200", PROCESSED_AT.minusMonths(6)),
                                leftAmount(12L, "150", PROCESSED_AT.minusYears(1).plusSeconds(1))));
        when(businessClockService.now()).thenReturn(PROCESSED_AT);

        assertThat(withdrawalService.getImmaturePrincipalAmount(ACCOUNT_ID))
                .isEqualByComparingTo("350");
    }

    @Test
    void completedWithdrawalImmatureAmountComesFromAllocationHistory() {
        when(withdrawalMapper.selectImmatureAllocatedAmountByWithdrawalId(WITHDRAWAL_ID))
                .thenReturn(new BigDecimal("400"));

        assertThat(withdrawalService.getImmatureAllocatedAmount(WITHDRAWAL_ID))
                .isEqualByComparingTo("400");
    }

    @Test
    void insufficientBalance_isRecordedAndOriginalExceptionIsRethrown() {
        WithdrawalRequestDTO request = request();
        WithdrawalFailureContext context = failureContext();
        AppException exception =
                new AppException(ErrorType.INSUFFICIENT_WITHDRAWAL_AMOUNT, context);
        when(withdrawalProcessor.withdraw(request)).thenThrow(exception);

        assertThatThrownBy(() -> withdrawalService.withdraw(request)).isSameAs(exception);

        verify(withdrawalFailureService).recordInsufficientBalance(context);
    }

    @Test
    void successfulWithdrawalReturnsProcessorResult() {
        WithdrawalRequestDTO request = request();
        WithdrawalResultDTO expected =
                WithdrawalResultDTO.builder().withdrawalId(WITHDRAWAL_ID).build();
        when(withdrawalProcessor.withdraw(request)).thenReturn(expected);

        WithdrawalResultDTO result = withdrawalService.withdraw(request);

        assertThat(result).isSameAs(expected);
        verify(withdrawalProcessor).withdraw(request);
        verify(withdrawalFailureService, never())
                .recordInsufficientBalance(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void nonRecordableFailure_doesNotCreateFailedWithdrawal() {
        WithdrawalRequestDTO request = request();
        AppException exception = new AppException(ErrorType.ACCOUNT_STATUS_NOT_WITHDRAWABLE);
        when(withdrawalProcessor.withdraw(request)).thenThrow(exception);

        assertThatThrownBy(() -> withdrawalService.withdraw(request)).isSameAs(exception);

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

        assertThatThrownBy(() -> withdrawalService.withdrawForClosure(request)).isSameAs(exception);

        verify(withdrawalFailureService).recordInsufficientBalance(context);
    }

    @Test
    void successfulClosureWithdrawalReturnsProcessorResult() {
        WithdrawalRequestDTO request = request();
        WithdrawalResultDTO expected =
                WithdrawalResultDTO.builder().withdrawalId(WITHDRAWAL_ID).build();
        when(withdrawalProcessor.withdrawForClosure(request)).thenReturn(expected);

        WithdrawalResultDTO result = withdrawalService.withdrawForClosure(request);

        assertThat(result).isSameAs(expected);
        verify(withdrawalProcessor).withdrawForClosure(request);
        verify(withdrawalFailureService, never())
                .recordInsufficientBalance(org.mockito.ArgumentMatchers.any());
    }

    private WithdrawalHistoryDTO history(Long withdrawalId, String requestedAmount) {
        return WithdrawalHistoryDTO.builder()
                .withdrawalId(withdrawalId)
                .accountId(1L)
                .customerName("인출 고객")
                .riaAccountNo("1234567890")
                .requestedAmount(new BigDecimal(requestedAmount))
                .processedAt(PROCESSED_AT)
                .destinationAccountNo("111122223333")
                .destinationGeneralAccountId(20L)
                .status(WithdrawalStatus.COMPLETED)
                .earningsAmount(new BigDecimal("100"))
                .maturedPrincipalAmount(new BigDecimal("300"))
                .immaturePrincipalAmount(new BigDecimal("400"))
                .allocationCount(4)
                .normalAllocationCount(3)
                .earlyAllocationCount(1)
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
