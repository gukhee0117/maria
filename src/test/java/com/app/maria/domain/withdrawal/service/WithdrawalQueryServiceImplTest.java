package com.app.maria.domain.withdrawal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.withdrawal.dto.WithdrawalAllocationHistoryDTO;
import com.app.maria.domain.withdrawal.dto.WithdrawalHistoryDTO;
import com.app.maria.domain.withdrawal.dto.response.WithdrawalDetailResponseDTO;
import com.app.maria.domain.withdrawal.dto.response.WithdrawalListResponseDTO;
import com.app.maria.domain.withdrawal.mapper.WithdrawalMapper;
import com.app.maria.domain.withdrawal.type.WithdrawalStatus;
import com.app.maria.domain.withdrawal.type.WithdrawalType;
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
class WithdrawalQueryServiceImplTest {
    private static final LocalDateTime PROCESSED_AT = LocalDateTime.of(2026, 8, 12, 10, 0);

    @Mock private WithdrawalMapper withdrawalMapper;
    @InjectMocks private WithdrawalQueryServiceImpl withdrawalQueryService;

    @Test
    void listConvertsMapperResultsWithoutChangingOrderOrAmounts() {
        when(withdrawalMapper.selectWithdrawalHistories(WithdrawalStatus.COMPLETED))
                .thenReturn(List.of(history(2L, "900"), history(1L, "800")));

        List<WithdrawalListResponseDTO> result =
                withdrawalQueryService.getWithdrawals(WithdrawalStatus.COMPLETED);

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

        List<WithdrawalListResponseDTO> result =
                withdrawalQueryService.getWithdrawalsByAccountId(1L);

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

        WithdrawalDetailResponseDTO result = withdrawalQueryService.getWithdrawal(1L);

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

        assertThatThrownBy(() -> withdrawalQueryService.getWithdrawal(99L))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.WITHDRAWAL_NOT_FOUND.getMessage())
                .hasMessage("인출 내역을 찾을 수 없습니다.");

        verify(withdrawalMapper, never()).selectAllocationHistoriesByWithdrawalId(99L);
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
}
