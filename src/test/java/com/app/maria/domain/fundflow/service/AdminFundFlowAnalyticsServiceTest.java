package com.app.maria.domain.fundflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.fundflow.dto.FundFlowAccountFlowRowDTO;
import com.app.maria.domain.fundflow.dto.FundFlowFilterDTO;
import com.app.maria.domain.fundflow.dto.FundFlowLeakageCategoryDTO;
import com.app.maria.domain.fundflow.dto.FundFlowMonthDTO;
import com.app.maria.domain.fundflow.dto.FundFlowNetBuyDTO;
import com.app.maria.domain.fundflow.dto.FundFlowOverviewDTO;
import com.app.maria.domain.fundflow.dto.FundFlowProductCostDTO;
import com.app.maria.domain.fundflow.dto.FundFlowRatioBinDTO;
import com.app.maria.domain.fundflow.dto.FundFlowReliefDTO;
import com.app.maria.domain.fundflow.dto.FundFlowRetentionLotRowDTO;
import com.app.maria.domain.fundflow.dto.FundFlowSegmentRowDTO;
import com.app.maria.domain.fundflow.dto.request.FundFlowMonthlyRequestDTO;
import com.app.maria.domain.fundflow.dto.request.FundFlowPeriodRequestDTO;
import com.app.maria.domain.fundflow.dto.request.FundFlowReliefRequestDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowAllocationResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowLeakageResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowMonthlyResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowOverviewResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowReliefResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowSegmentResponseDTO;
import com.app.maria.domain.fundflow.mapper.FundFlowMapper;
import com.app.maria.domain.fundflow.type.ReliefScope;
import com.app.maria.global.clock.service.BusinessClockService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminFundFlowAnalyticsServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 18, 0, 0);

    @Mock FundFlowMapper fundFlowMapper;

    @Mock BusinessClockService businessClockService;

    @InjectMocks AdminFundFlowServiceImpl service;

    @BeforeEach
    void stubClock() {
        when(businessClockService.now()).thenReturn(NOW);
    }

    @Test
    @DisplayName("개요: 재투자율은 합계 비율, 순투자율은 (매수-매도)/유입이며 소수 4자리로 낸다")
    void overviewComputesRatios() {
        when(fundFlowMapper.selectOverview(any()))
                .thenReturn(
                        FundFlowOverviewDTO.builder()
                                .openedAccounts(75046L)
                                .inflowTotal(new BigDecimal("6000"))
                                .domesticBuyTotal(new BigDecimal("4260"))
                                .domesticSellTotal(new BigDecimal("460"))
                                .build());

        FundFlowOverviewResponseDTO result = service.getOverview(new FundFlowPeriodRequestDTO());

        assertThat(result.getAsOfDate()).isEqualTo(LocalDate.of(2026, 11, 17));
        assertThat(result.getReinvestRatio()).isEqualByComparingTo("0.7100");
        assertThat(result.getNetInvestRatio()).isEqualByComparingTo("0.6333");
        assertThat(result.getBasis()).isNotBlank();
    }

    @Test
    @DisplayName("개요: 확정 유입이 0이면 비율은 null이다")
    void overviewReturnsNullRatiosWhenNoInflow() {
        when(fundFlowMapper.selectOverview(any()))
                .thenReturn(
                        FundFlowOverviewDTO.builder()
                                .openedAccounts(0L)
                                .inflowTotal(BigDecimal.ZERO)
                                .domesticBuyTotal(BigDecimal.ZERO)
                                .domesticSellTotal(BigDecimal.ZERO)
                                .build());

        FundFlowOverviewResponseDTO result = service.getOverview(new FundFlowPeriodRequestDTO());

        assertThat(result.getReinvestRatio()).isNull();
        assertThat(result.getNetInvestRatio()).isNull();
    }

    @Test
    @DisplayName("누수: 5분류를 enum 순서로 모두 내고, 없는 분류는 0명, 점유율은 전체 대비 4자리다")
    void leakageFillsAllFiveCategoriesInOrder() {
        when(fundFlowMapper.selectLeakage())
                .thenReturn(
                        List.of(
                                FundFlowLeakageCategoryDTO.builder()
                                        .code("PARTIAL_REDUCED")
                                        .accounts(1L)
                                        .withExternalBuy(1L)
                                        .build(),
                                FundFlowLeakageCategoryDTO.builder()
                                        .code("NO_SELL")
                                        .accounts(3L)
                                        .withExternalBuy(0L)
                                        .build()));
        when(fundFlowMapper.selectSnapshotCalculatedAt())
                .thenReturn(LocalDateTime.of(2026, 11, 17, 2, 0));

        FundFlowLeakageResponseDTO result = service.getLeakage();

        assertThat(result.getTotal()).isEqualTo(4L);
        assertThat(result.getSnapshotCalculatedAt())
                .isEqualTo(LocalDateTime.of(2026, 11, 17, 2, 0));
        assertThat(result.getCategories())
                .extracting(FundFlowLeakageResponseDTO.Category::getCode)
                .containsExactly(
                        "NO_SELL",
                        "BENEFIT_CANCELED",
                        "NO_IMPACT",
                        "PARTIAL_REDUCED",
                        "EXTERNAL_BUY_OFFSET");
        assertThat(result.getCategories().get(0).getLabel()).isEqualTo("매도 없음");
        assertThat(result.getCategories().get(0).getShare()).isEqualByComparingTo("0.7500");
        assertThat(result.getCategories().get(1).getAccounts()).isZero();
        assertThat(result.getCategories().get(3).getWithExternalBuy()).isEqualTo(1L);
    }

    @Test
    @DisplayName("누수: 스냅샷이 없으면 전체 0명이고 점유율은 null이다")
    void leakageWithEmptySnapshot() {
        when(fundFlowMapper.selectLeakage()).thenReturn(List.of());

        FundFlowLeakageResponseDTO result = service.getLeakage();

        assertThat(result.getTotal()).isZero();
        assertThat(result.getCategories()).hasSize(5);
        assertThat(result.getCategories()).allSatisfy(c -> assertThat(c.getShare()).isNull());
    }

    @Test
    @DisplayName("월별: 업무시계가 속한 달은 원천 집계로 partial=true로 이어 붙이고, 조회 범위를 매퍼에 넘긴다")
    void monthlyAppendsPartialCurrentMonth() {
        when(fundFlowMapper.selectMonthlyClosed(any()))
                .thenReturn(
                        List.of(
                                FundFlowMonthDTO.builder()
                                        .flowMonth(LocalDate.of(2026, 10, 1))
                                        .build()));
        when(fundFlowMapper.selectMonthlyPartial(any()))
                .thenReturn(FundFlowMonthDTO.builder().domesticBuyAmount(BigDecimal.TEN).build());
        FundFlowMonthlyRequestDTO request = new FundFlowMonthlyRequestDTO();
        request.setFromMonth(YearMonth.of(2026, 4));

        FundFlowMonthlyResponseDTO result = service.getMonthly(request);

        assertThat(result.getMonths()).hasSize(2);
        assertThat(result.getMonths().get(0).isPartial()).isFalse();
        assertThat(result.getMonths().get(1).isPartial()).isTrue();
        assertThat(result.getMonths().get(1).getFlowMonth()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(result.getMonths().get(1).getCashBalanceEnd()).isNull();
        ArgumentCaptor<FundFlowFilterDTO> captor = ArgumentCaptor.forClass(FundFlowFilterDTO.class);
        verify(fundFlowMapper).selectMonthlyClosed(captor.capture());
        assertThat(captor.getValue().getFromMonth()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(captor.getValue().getMonthStart()).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    @DisplayName("월별: 조회 범위 상한이 진행 중인 달보다 앞이면 진행 중인 달을 집계하지 않는다")
    void monthlyExcludesPartialMonthOutsideRange() {
        when(fundFlowMapper.selectMonthlyClosed(any())).thenReturn(List.of());
        FundFlowMonthlyRequestDTO request = new FundFlowMonthlyRequestDTO();
        request.setToMonth(YearMonth.of(2026, 10));

        FundFlowMonthlyResponseDTO result = service.getMonthly(request);

        assertThat(result.getMonths()).isEmpty();
        verify(fundFlowMapper, never()).selectMonthlyPartial(any());
    }

    @Test
    @DisplayName("월별: 업무시계가 월 1일이면 진행 중인 달은 아직 하루도 집계되지 않아 내지 않는다")
    void monthlyExcludesPartialMonthOnFirstDay() {
        when(businessClockService.now()).thenReturn(LocalDateTime.of(2026, 12, 1, 0, 0));
        when(fundFlowMapper.selectMonthlyClosed(any())).thenReturn(List.of());

        FundFlowMonthlyResponseDTO result = service.getMonthly(new FundFlowMonthlyRequestDTO());

        assertThat(result.getMonths()).isEmpty();
        verify(fundFlowMapper, never()).selectMonthlyPartial(any());
    }

    @Test
    @DisplayName("세그먼트: 연령대 소계와 전체를 합산하고, 5명 미만 칸은 합계·비율을 가린다")
    void segmentsAddSubtotalsAndMaskSmallCells() {
        when(fundFlowMapper.selectSegments(any()))
                .thenReturn(
                        List.of(
                                row("30대", "ACTIVE", 4, 1000, 500, 0),
                                row("30대", "NEUTRAL", 6, 2000, 1000, 200),
                                row("40대", "ACTIVE", 10, 1000, 100, 0)));

        FundFlowSegmentResponseDTO result = service.getSegments(new FundFlowPeriodRequestDTO());

        List<FundFlowSegmentResponseDTO.Cell> cells = result.getCells();
        assertThat(cells)
                .extracting(c -> c.getAgeBand() + "/" + c.getInvestorType())
                .containsExactly(
                        "30대/ACTIVE",
                        "30대/NEUTRAL",
                        "30대/null",
                        "40대/ACTIVE",
                        "40대/null",
                        "null/null");
        assertThat(cells.get(0).getAccounts()).isEqualTo(4L);
        assertThat(cells.get(0).getInflowTotal()).isNull();
        assertThat(cells.get(0).getReinvestRatio()).isNull();
        assertThat(cells.get(1).getReinvestRatio()).isEqualByComparingTo("0.5000");
        assertThat(cells.get(1).getNetInvestRatio()).isEqualByComparingTo("0.4000");
        assertThat(cells.get(2).getAccounts()).isEqualTo(10L);
        assertThat(cells.get(2).getInflowTotal()).isEqualByComparingTo("3000");
        assertThat(cells.get(5).getAccounts()).isEqualTo(20L);
        assertThat(cells.get(5).getReinvestRatio()).isEqualByComparingTo("0.4000");
    }

    @Test
    @DisplayName("감면액: 기본 범위는 ALL이고 감면 1원당 투자액은 소수 1자리다")
    void reliefDefaultsToAllAndComputesInvestPerRelief() {
        when(fundFlowMapper.selectRelief(any()))
                .thenReturn(
                        FundFlowReliefDTO.builder()
                                .accounts(2L)
                                .generalTaxTotal(new BigDecimal("2200000"))
                                .finalTaxTotal(new BigDecimal("1000000"))
                                .reliefTotal(new BigDecimal("1200000"))
                                .accountsWithRelief(1L)
                                .netInvestTotal(new BigDecimal("25200000"))
                                .build());

        FundFlowReliefResponseDTO result = service.getRelief(new FundFlowReliefRequestDTO());

        assertThat(result.getScope()).isEqualTo("ALL");
        assertThat(result.getInvestPerRelief()).isEqualByComparingTo("21.0");
        assertThat(result.getDefinitionVersion()).isEqualTo("draft-1");
        ArgumentCaptor<FundFlowFilterDTO> captor = ArgumentCaptor.forClass(FundFlowFilterDTO.class);
        verify(fundFlowMapper).selectRelief(captor.capture());
        assertThat(captor.getValue().getScope()).isEqualTo("ALL");
    }

    @Test
    @DisplayName("감면액: 감면 합계가 0이면 감면 1원당 투자액은 null이고 POSITIVE 범위를 매퍼에 넘긴다")
    void reliefWithoutReliefHasNullInvestPerRelief() {
        when(fundFlowMapper.selectRelief(any()))
                .thenReturn(
                        FundFlowReliefDTO.builder()
                                .accounts(0L)
                                .generalTaxTotal(BigDecimal.ZERO)
                                .finalTaxTotal(BigDecimal.ZERO)
                                .reliefTotal(BigDecimal.ZERO)
                                .accountsWithRelief(0L)
                                .netInvestTotal(BigDecimal.ZERO)
                                .build());
        FundFlowReliefRequestDTO request = new FundFlowReliefRequestDTO();
        request.setScope(ReliefScope.POSITIVE);

        FundFlowReliefResponseDTO result = service.getRelief(request);

        assertThat(result.getScope()).isEqualTo("POSITIVE");
        assertThat(result.getInvestPerRelief()).isNull();
        ArgumentCaptor<FundFlowFilterDTO> captor = ArgumentCaptor.forClass(FundFlowFilterDTO.class);
        verify(fundFlowMapper).selectRelief(captor.capture());
        assertThat(captor.getValue().getScope()).isEqualTo("POSITIVE");
    }

    @Test
    @DisplayName("운용처: 펀드는 ETF·펀드로 묶고 업종별 점유율과 종목·묶음 HHI를 계산한다")
    void allocationBucketsAndHhi() {
        when(fundFlowMapper.selectProductCosts())
                .thenReturn(
                        List.of(
                                product("삼성전자", "STOCK", "전기전자", "600"),
                                product("SK하이닉스", "STOCK", "전기전자", "200"),
                                product("KODEX 200", "FUND", null, "200")));
        when(fundFlowMapper.selectTopNetBuy(any()))
                .thenReturn(List.of(FundFlowNetBuyDTO.builder().name("삼성전자").build()));

        FundFlowAllocationResponseDTO result =
                service.getAllocation(new FundFlowPeriodRequestDTO());

        assertThat(result.getValueBasis()).isEqualTo("PURCHASE_COST");
        assertThat(result.getTotalValue()).isEqualByComparingTo("1000");
        assertThat(result.getBuckets())
                .extracting(FundFlowAllocationResponseDTO.Bucket::getLabel)
                .containsExactly("전기전자", "ETF·펀드");
        assertThat(result.getBuckets().get(0).getShare()).isEqualByComparingTo("0.8000");
        // 묶음 HHI = 0.8² + 0.2² = 0.68, 종목 HHI = 0.6² + 0.2² + 0.2² = 0.44
        assertThat(result.getHhiBucket()).isEqualByComparingTo("0.6800");
        assertThat(result.getHhiProduct()).isEqualByComparingTo("0.4400");
        assertThat(result.getTopNetBuy()).hasSize(1);
    }

    @Test
    @DisplayName("운용처: 보유가 없으면 HHI는 null이다")
    void allocationWithoutHoldings() {
        when(fundFlowMapper.selectProductCosts()).thenReturn(List.of());
        when(fundFlowMapper.selectTopNetBuy(any())).thenReturn(List.of());

        FundFlowAllocationResponseDTO result =
                service.getAllocation(new FundFlowPeriodRequestDTO());

        assertThat(result.getTotalValue()).isEqualByComparingTo("0");
        assertThat(result.getBuckets()).isEmpty();
        assertThat(result.getHhiBucket()).isNull();
        assertThat(result.getHhiProduct()).isNull();
    }

    private FundFlowSegmentRowDTO row(
            String ageBand, String investorType, long accounts, long inflow, long buy, long sell) {
        return FundFlowSegmentRowDTO.builder()
                .ageBand(ageBand)
                .investorType(investorType)
                .accounts(accounts)
                .accountsWithInflow(accounts)
                .inflowTotal(BigDecimal.valueOf(inflow))
                .buyTotal(BigDecimal.valueOf(buy))
                .sellTotal(BigDecimal.valueOf(sell))
                .build();
    }

    private FundFlowProductCostDTO product(String name, String type, String sector, String cost) {
        return FundFlowProductCostDTO.builder()
                .name(name)
                .type(type)
                .sectorName(sector)
                .purchaseCost(new BigDecimal(cost))
                .build();
    }

    @Test
    @DisplayName("조정비율 구간: 비어 있는 구간도 순서대로 0으로 채우고 비율은 매도한 계좌 대비다")
    void leakageFillsAllRatioBinsInOrder() {
        when(fundFlowMapper.selectLeakage()).thenReturn(List.of());
        when(fundFlowMapper.selectRatioBins())
                .thenReturn(
                        List.of(
                                FundFlowRatioBinDTO.builder().code("FULL").accounts(3L).build(),
                                FundFlowRatioBinDTO.builder().code("ZERO").accounts(1L).build()));

        var result = service.getLeakage();

        assertThat(result.getRatioBins())
                .extracting(b -> b.getCode() + ":" + b.getAccounts())
                .containsExactly(
                        "ZERO:1",
                        "UNDER_25:0",
                        "UNDER_50:0",
                        "UNDER_75:0",
                        "UNDER_100:0",
                        "FULL:3");
        assertThat(result.getRatioBins().get(5).getShare()).isEqualByComparingTo("0.7500");
    }

    @Test
    @DisplayName("연령대 중앙값: 유입이 있는 계좌가 5개 미만이면 가리고, 5개 이상이면 평균이 아닌 중앙값을 낸다")
    void ageMediansMaskSmallBandsAndUseMedian() {
        when(fundFlowMapper.selectSegments(any())).thenReturn(List.of());
        List<FundFlowAccountFlowRowDTO> rows = new java.util.ArrayList<>();
        // 30대 5개: 유입 100, 200, 300, 400, 10,000 (극단값) / 매수 50, 100, 150, 200, 10,000
        long[] inflows = {100, 200, 300, 400, 10000};
        long[] buys = {50, 100, 150, 200, 10000};
        for (int i = 0; i < 5; i++) {
            rows.add(flow("30대", inflows[i], buys[i]));
        }
        // 40대 4개: 가림 대상
        for (int i = 0; i < 4; i++) {
            rows.add(flow("40대", 100, 10));
        }
        // 50대 1개는 유입 없음
        rows.add(flow("50대", 0, 0));
        when(fundFlowMapper.selectAccountFlows(any())).thenReturn(rows);

        var result = service.getSegments(new FundFlowPeriodRequestDTO());

        var byBand =
                result.getAgeMedians().stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        m -> String.valueOf(m.getAgeBand()), m -> m));
        assertThat(byBand.get("30대").getInflowMedian()).isEqualByComparingTo("300");
        assertThat(byBand.get("30대").getReinvestMedian()).isEqualByComparingTo("0.5");
        assertThat(byBand.get("40대").getInflowMedian()).isNull();
        assertThat(byBand.get("40대").getAccountsWithInflow()).isEqualTo(4L);
        assertThat(byBand.get("50대").getAccountsWithInflow()).isZero();
        assertThat(byBand.get("null").getAccounts()).isEqualTo(10L);
    }

    @Test
    @DisplayName("혜택 집중도: 감면액 목록으로 지니계수·상위 10%·로렌츠 21점을 낸다")
    void concentrationUsesReliefAmounts() {
        when(fundFlowMapper.selectReliefAmounts(any()))
                .thenReturn(List.of(new BigDecimal("1"), new BigDecimal("3")));

        var result = service.getConcentration();

        assertThat(result.getAccounts()).isEqualTo(2L);
        assertThat(result.getReliefTotal()).isEqualByComparingTo("4");
        assertThat(result.getGini()).isEqualByComparingTo("0.25");
        assertThat(result.getTop10Share()).isEqualByComparingTo("0.75");
        assertThat(result.getLorenz()).hasSize(21);
    }

    @Test
    @DisplayName("원금 유지: 건 단위 행으로 곡선을 만들고 사건 수와 최대 관찰일을 낸다")
    void retentionBuildsCurveFromLotRows() {
        when(fundFlowMapper.selectRetentionLots(any()))
                .thenReturn(
                        List.of(
                                lot(1L, "1000", 5, true),
                                lot(2L, "1000", 10, false),
                                lot(3L, "1000", 10, true),
                                lot(4L, "1000", 20, false)));

        var result = service.getRetention(new FundFlowPeriodRequestDTO());

        assertThat(result.getLots()).isEqualTo(4L);
        assertThat(result.getAccounts()).isEqualTo(4L);
        assertThat(result.getEvents()).isEqualTo(2L);
        assertThat(result.getMaxObservedDays()).isEqualTo(20);
        assertThat(result.getBootstrapReplicates()).isEqualTo(200);
        assertThat(result.getPoints())
                .extracting(pt -> pt.getDay() + ":" + pt.getSurvival().toPlainString())
                .containsExactly("0:1.0000", "10:0.5000", "20:0.5000");
    }

    @Test
    @DisplayName("원금 유지: 확정된 건이 없으면 빈 곡선(생존 1)을 낸다")
    void retentionWithoutLotsReturnsFlatCurve() {
        when(fundFlowMapper.selectRetentionLots(any())).thenReturn(List.of());

        var result = service.getRetention(new FundFlowPeriodRequestDTO());

        assertThat(result.getLots()).isZero();
        assertThat(result.getBootstrapReplicates()).isZero();
        assertThat(result.getPoints()).hasSize(1);
        assertThat(result.getPoints().get(0).getSurvival()).isEqualByComparingTo("1");
    }

    private static FundFlowAccountFlowRowDTO flow(String ageBand, long inflow, long buy) {
        return FundFlowAccountFlowRowDTO.builder()
                .ageBand(ageBand)
                .inflow(BigDecimal.valueOf(inflow))
                .buyExec(BigDecimal.valueOf(buy))
                .build();
    }

    private static FundFlowRetentionLotRowDTO lot(
            long accountId, String amount, int days, boolean event) {
        return FundFlowRetentionLotRowDTO.builder()
                .accountId(accountId)
                .finalAmount(new BigDecimal(amount))
                .days(days)
                .event(event)
                .build();
    }
}
