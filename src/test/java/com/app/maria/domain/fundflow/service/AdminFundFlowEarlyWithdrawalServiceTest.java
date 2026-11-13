package com.app.maria.domain.fundflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.fundflow.dto.FundFlowEarlyWithdrawalCauseDTO;
import com.app.maria.domain.fundflow.dto.FundFlowEarlyWithdrawalSummaryRowDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowEarlyWithdrawalResponseDTO;
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
class AdminFundFlowEarlyWithdrawalServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 18, 9, 30, 15);

    @Mock FundFlowMapper fundFlowMapper;

    @Mock BusinessClockService businessClockService;

    @InjectMocks AdminFundFlowServiceImpl adminFundFlowService;

    private static FundFlowEarlyWithdrawalSummaryRowDTO summary(
            long finalizedAccounts,
            String finalizedAmount,
            long earlyAccounts,
            String earlyAmount) {
        return FundFlowEarlyWithdrawalSummaryRowDTO.builder()
                .finalizedAccounts(finalizedAccounts)
                .finalizedAmount(new BigDecimal(finalizedAmount))
                .earlyAccounts(earlyAccounts)
                .earlyWithdrawnAmount(new BigDecimal(earlyAmount))
                .build();
    }

    private static FundFlowEarlyWithdrawalCauseDTO cause(
            String cause, long accounts, String amount) {
        return FundFlowEarlyWithdrawalCauseDTO.builder()
                .cause(cause)
                .accounts(accounts)
                .amount(new BigDecimal(amount))
                .build();
    }

    @Test
    @DisplayName("정상: 분모·분자와 비율(소수 4자리 반올림)·원인별 값을 응답에 담는다")
    void getEarlyWithdrawalMapsSummaryAndShares() {
        when(businessClockService.now()).thenReturn(NOW);
        when(fundFlowMapper.selectEarlyWithdrawalSummary(any()))
                .thenReturn(summary(75046L, "6026600000000", 16L, "101362973"));
        when(fundFlowMapper.selectEarlyWithdrawalByCause(any()))
                .thenReturn(
                        List.of(
                                cause("CLOSURE_FORCED", 15L, "95000000"),
                                cause("OTHER", 1L, "6362973")));

        FundFlowEarlyWithdrawalResponseDTO result = adminFundFlowService.getEarlyWithdrawal();

        assertThat(result.getAsOfDate()).isEqualTo(LocalDate.of(2026, 11, 17));
        assertThat(result.getFinalizedAccounts()).isEqualTo(75046L);
        assertThat(result.getFinalizedAmount()).isEqualByComparingTo("6026600000000");
        assertThat(result.getEarlyAccounts()).isEqualTo(16L);
        assertThat(result.getEarlyWithdrawnAmount()).isEqualByComparingTo("101362973");
        // 16 / 75046 = 0.000213... → 0.0002, 101362973 / 6026600000000 = 0.0000168... → 0.0000
        assertThat(result.getAccountShare()).isEqualByComparingTo("0.0002");
        assertThat(result.getAmountShare()).isEqualByComparingTo("0.0000");
        assertThat(result.getBasis())
                .contains("분모 대상 환전에서 나온 인출만")
                .contains("인출액")
                .contains("확정 원금");
    }

    @Test
    @DisplayName("원인은 해지 강제 인출 → 그 밖 순서로 고정하고, 없는 원인은 0으로 채운다")
    void getEarlyWithdrawalFixesCauseOrderAndFillsMissingCauseWithZero() {
        when(businessClockService.now()).thenReturn(NOW);
        when(fundFlowMapper.selectEarlyWithdrawalSummary(any()))
                .thenReturn(summary(10L, "1000000", 2L, "300000"));
        // 매퍼가 OTHER 만 주고 순서도 뒤바뀐 경우
        when(fundFlowMapper.selectEarlyWithdrawalByCause(any()))
                .thenReturn(List.of(cause("OTHER", 2L, "300000")));

        FundFlowEarlyWithdrawalResponseDTO result = adminFundFlowService.getEarlyWithdrawal();

        assertThat(result.getByCause()).hasSize(2);
        assertThat(result.getByCause().get(0).getCause()).isEqualTo("CLOSURE_FORCED");
        assertThat(result.getByCause().get(0).getLabel()).isEqualTo("해지 강제 인출");
        assertThat(result.getByCause().get(0).getAccounts()).isZero();
        assertThat(result.getByCause().get(0).getAmount()).isEqualByComparingTo("0");
        assertThat(result.getByCause().get(1).getCause()).isEqualTo("OTHER");
        assertThat(result.getByCause().get(1).getLabel()).isEqualTo("그 밖의 인출");
        assertThat(result.getByCause().get(1).getAccounts()).isEqualTo(2L);
        assertThat(result.getByCause().get(1).getAmount()).isEqualByComparingTo("300000");
    }

    @Test
    @DisplayName("해지 강제 인출 분류: 해지 강제와 그 밖의 값이 각 원인 항목에 그대로 들어간다")
    void getEarlyWithdrawalClassifiesClosureForcedSeparately() {
        when(businessClockService.now()).thenReturn(NOW);
        when(fundFlowMapper.selectEarlyWithdrawalSummary(any()))
                .thenReturn(summary(5L, "55000000", 3L, "12500000"));
        when(fundFlowMapper.selectEarlyWithdrawalByCause(any()))
                .thenReturn(
                        List.of(
                                cause("OTHER", 2L, "3000000"),
                                cause("CLOSURE_FORCED", 2L, "9500000")));

        FundFlowEarlyWithdrawalResponseDTO result = adminFundFlowService.getEarlyWithdrawal();

        assertThat(result.getByCause().get(0).getCause()).isEqualTo("CLOSURE_FORCED");
        assertThat(result.getByCause().get(0).getAmount()).isEqualByComparingTo("9500000");
        assertThat(result.getByCause().get(1).getCause()).isEqualTo("OTHER");
        assertThat(result.getByCause().get(1).getAmount()).isEqualByComparingTo("3000000");
        // 한 계좌가 두 원인에 걸칠 수 있어 원인별 계좌 수 합(4)이 전체(3)보다 클 수 있다
        long causeAccounts =
                result.getByCause().stream()
                        .mapToLong(FundFlowEarlyWithdrawalResponseDTO.Cause::getAccounts)
                        .sum();
        assertThat(causeAccounts).isGreaterThanOrEqualTo(result.getEarlyAccounts());
    }

    @Test
    @DisplayName("데이터 없음: 분모가 0이면 두 비율은 null, 원인은 0 두 항목이다")
    void getEarlyWithdrawalReturnsNullSharesWhenDenominatorIsZero() {
        when(businessClockService.now()).thenReturn(NOW);
        when(fundFlowMapper.selectEarlyWithdrawalSummary(any()))
                .thenReturn(summary(0L, "0", 0L, "0"));
        when(fundFlowMapper.selectEarlyWithdrawalByCause(any())).thenReturn(List.of());

        FundFlowEarlyWithdrawalResponseDTO result = adminFundFlowService.getEarlyWithdrawal();

        assertThat(result.getFinalizedAccounts()).isZero();
        assertThat(result.getEarlyAccounts()).isZero();
        assertThat(result.getAccountShare()).isNull();
        assertThat(result.getAmountShare()).isNull();
        assertThat(result.getByCause()).hasSize(2);
        assertThat(result.getByCause())
                .allSatisfy(
                        c -> {
                            assertThat(c.getAccounts()).isZero();
                            assertThat(c.getAmount()).isEqualByComparingTo("0");
                        });
        assertThat(result.getBasis()).isNotBlank();
    }

    @Test
    @DisplayName("분자가 0이어도 분모가 있으면 비율은 0.0000이다")
    void getEarlyWithdrawalReturnsZeroSharesWhenNoEarlyWithdrawal() {
        when(businessClockService.now()).thenReturn(NOW);
        when(fundFlowMapper.selectEarlyWithdrawalSummary(any()))
                .thenReturn(summary(100L, "5000000", 0L, "0"));
        when(fundFlowMapper.selectEarlyWithdrawalByCause(any())).thenReturn(List.of());

        FundFlowEarlyWithdrawalResponseDTO result = adminFundFlowService.getEarlyWithdrawal();

        assertThat(result.getAccountShare()).isEqualByComparingTo("0.0000");
        assertThat(result.getAmountShare()).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("일자 상한은 업무시계 날짜(clockDate)로 두 쿼리에 넘긴다")
    void getEarlyWithdrawalPassesClockDate() {
        when(businessClockService.now()).thenReturn(NOW);
        when(fundFlowMapper.selectEarlyWithdrawalSummary(any()))
                .thenReturn(summary(0L, "0", 0L, "0"));
        when(fundFlowMapper.selectEarlyWithdrawalByCause(any())).thenReturn(List.of());

        adminFundFlowService.getEarlyWithdrawal();

        ArgumentCaptor<LocalDate> summaryDate = ArgumentCaptor.forClass(LocalDate.class);
        verify(fundFlowMapper).selectEarlyWithdrawalSummary(summaryDate.capture());
        assertThat(summaryDate.getValue()).isEqualTo(LocalDate.of(2026, 11, 18));
        ArgumentCaptor<LocalDate> causeDate = ArgumentCaptor.forClass(LocalDate.class);
        verify(fundFlowMapper).selectEarlyWithdrawalByCause(causeDate.capture());
        assertThat(causeDate.getValue()).isEqualTo(LocalDate.of(2026, 11, 18));
    }
}
