package com.app.maria.domain.fundflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.fundflow.dto.FundFlowMaturityMonthRowDTO;
import com.app.maria.domain.fundflow.dto.FundFlowMaturitySummaryRowDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowMaturityResponseDTO;
import com.app.maria.domain.fundflow.mapper.FundFlowMapper;
import com.app.maria.global.clock.service.BusinessClockService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminFundFlowMaturityServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 18, 9, 30, 15);

    @Mock FundFlowMapper fundFlowMapper;

    @Mock BusinessClockService businessClockService;

    @InjectMocks AdminFundFlowServiceImpl adminFundFlowService;

    private static FundFlowMaturityMonthRowDTO monthRow(
            int year, int month, long lots, long accounts, String paid, String remaining) {
        return monthRow(year, month, lots, accounts, paid, remaining, "0");
    }

    private static FundFlowMaturityMonthRowDTO monthRow(
            int year,
            int month,
            long lots,
            long accounts,
            String paid,
            String remaining,
            String canceled) {
        return FundFlowMaturityMonthRowDTO.builder()
                .maturityYear(year)
                .maturityMonth(month)
                .lots(lots)
                .accounts(accounts)
                .paidAmount(new BigDecimal(paid))
                .remainingAmount(new BigDecimal(remaining))
                .canceledRemainingAmount(new BigDecimal(canceled))
                .build();
    }

    private static FundFlowMaturitySummaryRowDTO summary(
            String locked, long accounts, String matured) {
        return summary(locked, accounts, matured, "0", 0L, 0L);
    }

    private static FundFlowMaturitySummaryRowDTO summary(
            String locked,
            long accounts,
            String matured,
            String canceled,
            long canceledAccounts,
            long lotsWithoutLeftAmount) {
        return FundFlowMaturitySummaryRowDTO.builder()
                .lockedAmount(new BigDecimal(locked))
                .lockedAccounts(accounts)
                .maturedRemainingAmount(new BigDecimal(matured))
                .canceledRemainingAmount(new BigDecimal(canceled))
                .canceledAccounts(canceledAccounts)
                .lotsWithoutLeftAmount(lotsWithoutLeftAmount)
                .build();
    }

    @Test
    @DisplayName("정상: 만기월을 그 달 1일로 만들고 첫 만기월·묶인 잔액·해지 제외분을 응답에 담는다")
    void getMaturityMapsRowsAndSummary() {
        when(businessClockService.now()).thenReturn(NOW);
        when(fundFlowMapper.selectMaturityMonths(any()))
                .thenReturn(
                        List.of(
                                monthRow(2027, 3, 3, 2, "33000000", "20000000"),
                                monthRow(2027, 5, 2, 2, "10000000", "10000000")));
        when(fundFlowMapper.selectMaturitySummary(any(), any()))
                .thenReturn(summary("30000000", 3L, "0"));
        when(fundFlowMapper.selectClosedPaidAmount(any())).thenReturn(new BigDecimal("9000000"));

        FundFlowMaturityResponseDTO result = adminFundFlowService.getMaturity();

        assertThat(result.getAsOfDate()).isEqualTo(LocalDate.of(2026, 11, 17));
        assertThat(result.getFirstMaturityMonth()).isEqualTo(LocalDate.of(2027, 3, 1));
        assertThat(result.getLockedAmount()).isEqualByComparingTo("30000000");
        assertThat(result.getLockedAccounts()).isEqualTo(3L);
        assertThat(result.getMaturedRemainingAmount()).isEqualByComparingTo("0");
        assertThat(result.getExcludedClosedPaidAmount()).isEqualByComparingTo("9000000");
        assertThat(result.getMonths()).hasSize(2);
        assertThat(result.getMonths().get(0).getMaturityMonth())
                .isEqualTo(LocalDate.of(2027, 3, 1));
        assertThat(result.getMonths().get(0).getLots()).isEqualTo(3L);
        assertThat(result.getMonths().get(0).getAccounts()).isEqualTo(2L);
        assertThat(result.getMonths().get(0).getPaidAmount()).isEqualByComparingTo("33000000");
        assertThat(result.getMonths().get(0).getRemainingAmount()).isEqualByComparingTo("20000000");
        assertThat(result.getMonths().get(1).getMaturityMonth())
                .isEqualTo(LocalDate.of(2027, 5, 1));
        assertThat(result.getBasis()).contains("해지(CLOSED)").contains("2/29");
    }

    @Test
    @DisplayName("대상 건 상한은 업무시계 날짜(clockDate)로, 묶임 경계는 업무시각 - 1년으로 매퍼에 넘긴다")
    void getMaturityPassesClockDateAndLockBoundary() {
        when(businessClockService.now()).thenReturn(NOW);
        when(fundFlowMapper.selectMaturityMonths(any())).thenReturn(List.of());
        when(fundFlowMapper.selectMaturitySummary(any(), any())).thenReturn(summary("0", 0L, "0"));
        when(fundFlowMapper.selectClosedPaidAmount(any())).thenReturn(BigDecimal.ZERO);

        adminFundFlowService.getMaturity();

        ArgumentCaptor<LocalDate> monthsDate = ArgumentCaptor.forClass(LocalDate.class);
        verify(fundFlowMapper).selectMaturityMonths(monthsDate.capture());
        assertThat(monthsDate.getValue()).isEqualTo(LocalDate.of(2026, 11, 18));

        ArgumentCaptor<LocalDate> summaryDate = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDateTime> boundary = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(fundFlowMapper).selectMaturitySummary(summaryDate.capture(), boundary.capture());
        assertThat(summaryDate.getValue()).isEqualTo(LocalDate.of(2026, 11, 18));
        assertThat(boundary.getValue()).isEqualTo(LocalDateTime.of(2025, 11, 18, 9, 30, 15));

        ArgumentCaptor<LocalDate> closedDate = ArgumentCaptor.forClass(LocalDate.class);
        verify(fundFlowMapper).selectClosedPaidAmount(closedDate.capture());
        assertThat(closedDate.getValue()).isEqualTo(LocalDate.of(2026, 11, 18));
    }

    @Test
    @DisplayName("데이터 없음: 월 목록은 비고 첫 만기월은 null, 금액은 0이다")
    void getMaturityReturnsEmptyWhenNoLots() {
        when(businessClockService.now()).thenReturn(NOW);
        when(fundFlowMapper.selectMaturityMonths(any())).thenReturn(List.of());
        when(fundFlowMapper.selectMaturitySummary(any(), any())).thenReturn(summary("0", 0L, "0"));
        when(fundFlowMapper.selectClosedPaidAmount(any())).thenReturn(BigDecimal.ZERO);

        FundFlowMaturityResponseDTO result = adminFundFlowService.getMaturity();

        assertThat(result.getMonths()).isEmpty();
        assertThat(result.getFirstMaturityMonth()).isNull();
        assertThat(result.getLockedAmount()).isEqualByComparingTo("0");
        assertThat(result.getLockedAccounts()).isZero();
        assertThat(result.getMaturedRemainingAmount()).isEqualByComparingTo("0");
        assertThat(result.getCanceledRemainingAmount()).isEqualByComparingTo("0");
        assertThat(result.getCanceledAccounts()).isZero();
        assertThat(result.getLotsWithoutLeftAmount()).isZero();
        assertThat(result.getExcludedClosedPaidAmount()).isEqualByComparingTo("0");
        assertThat(result.getBasis()).isNotBlank();
    }

    @Test
    @DisplayName("혜택 취소 잔액·취소 계좌 수·left_amount 없는 건 수를 요약과 월별에 그대로 담고 basis 에 정의를 적는다")
    void getMaturityMapsCanceledAndMissingLeftAmountFields() {
        when(businessClockService.now()).thenReturn(NOW);
        when(fundFlowMapper.selectMaturityMonths(any()))
                .thenReturn(
                        List.of(
                                monthRow(2027, 3, 3, 2, "33000000", "20000000", "0"),
                                monthRow(2027, 4, 2, 1, "8000000", "0", "6000000")));
        when(fundFlowMapper.selectMaturitySummary(any(), any()))
                .thenReturn(summary("20000000", 2L, "0", "6000000", 1L, 1L));
        when(fundFlowMapper.selectClosedPaidAmount(any())).thenReturn(BigDecimal.ZERO);

        FundFlowMaturityResponseDTO result = adminFundFlowService.getMaturity();

        assertThat(result.getLockedAmount()).isEqualByComparingTo("20000000");
        assertThat(result.getLockedAccounts()).isEqualTo(2L);
        assertThat(result.getCanceledRemainingAmount()).isEqualByComparingTo("6000000");
        assertThat(result.getCanceledAccounts()).isEqualTo(1L);
        assertThat(result.getLotsWithoutLeftAmount()).isEqualTo(1L);
        assertThat(result.getMonths().get(0).getCanceledRemainingAmount())
                .isEqualByComparingTo("0");
        assertThat(result.getMonths().get(1).getRemainingAmount()).isEqualByComparingTo("0");
        assertThat(result.getMonths().get(1).getCanceledRemainingAmount())
                .isEqualByComparingTo("6000000");
        assertThat(result.getBasis())
                .contains("혜택이 취소된")
                .contains("canceledRemainingAmount")
                .contains("lotsWithoutLeftAmount");
    }

    @Test
    @DisplayName("해지 계좌 확정 원금은 months 에 넣지 않고 excludedClosedPaidAmount 로만 낸다")
    void getMaturityKeepsClosedPaidAmountOutOfMonths() {
        when(businessClockService.now()).thenReturn(NOW);
        when(fundFlowMapper.selectMaturityMonths(any()))
                .thenReturn(List.of(monthRow(2027, 3, 1, 1, "10000000", "10000000")));
        when(fundFlowMapper.selectMaturitySummary(any(), any()))
                .thenReturn(summary("10000000", 1L, "0"));
        when(fundFlowMapper.selectClosedPaidAmount(any())).thenReturn(new BigDecimal("9000000"));

        FundFlowMaturityResponseDTO result = adminFundFlowService.getMaturity();

        assertThat(result.getMonths().get(0).getPaidAmount()).isEqualByComparingTo("10000000");
        assertThat(result.getExcludedClosedPaidAmount()).isEqualByComparingTo("9000000");
    }

    @Test
    @DisplayName("업무시각 경계(직전·정각·직후): 매퍼에 넘기는 경계 판정이 기존 plusYears(1) 판정과 같다")
    void lockBoundaryMatchesPlusYearsRuleAroundBoundary() {
        when(businessClockService.now()).thenReturn(NOW);
        when(fundFlowMapper.selectMaturityMonths(any())).thenReturn(List.of());
        when(fundFlowMapper.selectMaturitySummary(any(), any())).thenReturn(summary("0", 0L, "0"));
        when(fundFlowMapper.selectClosedPaidAmount(any())).thenReturn(BigDecimal.ZERO);

        adminFundFlowService.getMaturity();

        ArgumentCaptor<LocalDateTime> boundary = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(fundFlowMapper).selectMaturitySummary(any(), boundary.capture());
        LocalDateTime lockBoundary = boundary.getValue();

        // 만기 당일(2025-11-18 09:30:15 확정분은 오늘 09:30:15 에 만기)의 직전·정각·직후
        for (LocalDateTime finalAt :
                List.of(
                        lockBoundary.minusSeconds(1),
                        lockBoundary,
                        lockBoundary.plusSeconds(1),
                        lockBoundary.minusDays(1),
                        lockBoundary.plusDays(1))) {
            boolean lockedByBoundary = finalAt.isAfter(lockBoundary);
            boolean lockedByExistingRule = finalAt.plusYears(1).isAfter(NOW);
            assertThat(lockedByBoundary).as("finalAt=%s", finalAt).isEqualTo(lockedByExistingRule);
        }
        // 정각은 만기(묶임 아님), 직후는 묶임
        assertThat(lockBoundary.isAfter(lockBoundary)).isFalse();
        assertThat(lockBoundary.plusSeconds(1).isAfter(lockBoundary)).isTrue();
    }

    @Test
    @DisplayName("알려진 차이: 2/29 확정분은 경계 판정과 plusYears(1) 판정이 하루 어긋날 수 있다")
    void leapDayFinalAtCanDifferByOneDay() {
        LocalDateTime now = LocalDateTime.of(2025, 2, 28, 12, 0, 0);
        LocalDateTime finalAt = LocalDateTime.of(2024, 2, 29, 12, 0, 0);

        boolean lockedByExistingRule = finalAt.plusYears(1).isAfter(now); // 2025-02-28 12:00 → 만기
        boolean lockedByBoundary = finalAt.isAfter(now.minusYears(1)); // 2024-02-28 12:00 → 묶임

        assertThat(lockedByExistingRule).isFalse();
        assertThat(lockedByBoundary).isTrue();
    }
}
