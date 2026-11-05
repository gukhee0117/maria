package com.app.maria.domain.stockpackage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.investorassessment.dto.response.InvestorAssessmentStatusResponseDTO;
import com.app.maria.domain.investorassessment.service.InvestorAssessmentService;
import com.app.maria.domain.risk.dto.UsableRiskGradeDTO;
import com.app.maria.domain.risk.service.RiskGradeService;
import com.app.maria.domain.risk.type.RiskDataStatus;
import com.app.maria.domain.stockpackage.dto.response.StockTendencyResponseDTO;
import com.app.maria.domain.stockpackage.mapper.StockPackageMapper;
import com.app.maria.domain.stockpackage.mapper.StockTendencyMapper;
import com.app.maria.domain.stockpackage.type.StockPackageSentenceTest;
import com.app.maria.domain.stockpackage.vo.StockProductThemeVO;
import com.app.maria.domain.stockpackage.vo.TradePeriodVO;
import com.app.maria.domain.stockpackage.vo.TradeProductSummaryVO;
import com.app.maria.global.clock.service.BusinessClockService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StockTendencyServiceImplTest {
    private static final Long CUSTOMER_ID = 1L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 10, 0);

    @Mock private StockTendencyMapper tendencyMapper;
    @Mock private StockPackageMapper stockPackageMapper;
    @Mock private InvestorAssessmentService assessmentService;
    @Mock private RiskGradeService riskGradeService;
    @Mock private BusinessClockService clock;
    @InjectMocks private StockTendencyServiceImpl service;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient()
                .doCallRealMethod()
                .when(riskGradeService)
                .findUsableGrades(org.mockito.ArgumentMatchers.anyCollection());
        org.mockito.Mockito.doCallRealMethod().when(riskGradeService).findUsableGrades();
        when(clock.now()).thenReturn(NOW);
        // 위험중립형(4등급까지). 1번 종목 5등급, 2번 종목 2등급, 3번은 등급 없음
        when(assessmentService.getMine(CUSTOMER_ID))
                .thenReturn(
                        InvestorAssessmentStatusResponseDTO.of(
                                InvestorType.NEUTRAL,
                                NOW.minusMonths(1),
                                NOW.plusMonths(23),
                                false));
        when(riskGradeService.findUsable()).thenReturn(List.of(grade(1L, 5), grade(2L, 2)));
        when(stockPackageMapper.selectThemesByProductIds(anyList()))
                .thenReturn(List.of(theme(1L, "반도체"), theme(2L, "2차전지"), theme(2L, "반도체")));
        when(tendencyMapper.selectTradeSummariesByCustomerId(CUSTOMER_ID))
                .thenReturn(
                        List.of(
                                summary(1L, "전기전자", "6000000", 3, 1),
                                summary(2L, "화학", "4000000", 2, 0)));
        when(tendencyMapper.selectTradePeriodByCustomerId(CUSTOMER_ID))
                .thenReturn(new TradePeriodVO(6, LocalDateTime.of(2026, 9, 10, 9, 0)));
    }

    @Test
    @DisplayName("체결된 주문으로 거래에서 본 경향을 계산한다")
    void tradingMetrics() {
        StockTendencyResponseDTO r = service.getTendency(CUSTOMER_ID);

        var t = r.getTrading();
        assertThat(t.isAvailable()).isTrue();
        assertThat(t.getBuyCount()).isEqualTo(5);
        assertThat(t.getSellCount()).isEqualTo(1);
        assertThat(t.getOrderCount()).isEqualTo(6);
        assertThat(t.getOrdersPerMonth()).isEqualByComparingTo("2.0");
        assertThat(t.getSellRatioPercent()).isEqualByComparingTo("16.7");
        assertThat(t.getAvgBuyGrade()).isEqualByComparingTo("3.80");
        assertThat(t.getGradeCoveragePercent()).isEqualByComparingTo("100.0");
        assertThat(t.getGradeDistribution())
                .extracting(g -> g.getGrade() + ":" + g.getSharePercent().toPlainString())
                .containsExactly("2:40.0", "5:60.0");
        assertThat(t.getTopSector().getName()).isEqualTo("전기전자");
        assertThat(t.getTopSector().getWeight()).isEqualByComparingTo("60.0");
        assertThat(t.getTopTheme().getName()).isEqualTo("반도체");
        assertThat(t.getTopTheme().getWeight()).isEqualByComparingTo("100.0");
    }

    @Test
    @DisplayName("설문 성향과 거래 경향의 차이를 사실로만 비교한다")
    void comparison() {
        StockTendencyResponseDTO r = service.getTendency(CUSTOMER_ID);

        assertThat(r.getSurvey().isAvailable()).isTrue();
        assertThat(r.getSurvey().getLabel()).isEqualTo("위험중립형");
        assertThat(r.getSurvey().getAllowedMinGrade()).isEqualTo(4);
        assertThat(r.getComparison().isAvailable()).isTrue();
        assertThat(r.getComparison().getOutOfRangeBuySharePercent()).isEqualByComparingTo("40.0");
        String all = allText(r);
        assertThat(all)
                .contains("내 설문 투자성향은 위험중립형이고, 허용하는 가장 높은 위험은 4등급이에요.")
                .contains("체결된 매수 금액 기준으로 위험등급 가중평균은 3.80등급이에요.")
                .contains("매수 금액을 등급별로 나누면 2등급 40.0%, 5등급 60.0%예요.")
                .contains("설문 투자성향이 허용하는 가장 높은 위험은 4등급이고, 실제 매수의 위험등급 가중평균은 3.80등급이에요.")
                .contains("실제 매수의 가중평균은 설문 투자성향 범위를 벗어나 있어요.")
                .contains("설문 투자성향 범위를 벗어난 등급의 종목에 쓴 매수 금액은 전체의 40.0%예요.")
                .contains("공식 투자성향이 아니에요.");
        assertThat(r.getDisclaimer()).isEqualTo("투자 권유가 아닌 정보 제공이에요");
    }

    @Test
    @DisplayName("매수가 5건보다 적으면 거래 경향을 계산하지 않는다")
    void insufficient() {
        when(tendencyMapper.selectTradeSummariesByCustomerId(CUSTOMER_ID))
                .thenReturn(List.of(summary(1L, "전기전자", "6000000", 4, 0)));

        StockTendencyResponseDTO r = service.getTendency(CUSTOMER_ID);

        assertThat(r.getTrading().isAvailable()).isFalse();
        assertThat(r.getTrading().getAvgBuyGrade()).isNull();
        assertThat(r.getTrading().getBuyCount()).isEqualTo(4);
        assertThat(r.getComparison().isAvailable()).isFalse();
        assertThat(allText(r))
                .contains("체결된 매수가 5건보다 적어 거래에서 본 경향은 확인할 수 없어요.")
                .contains("한쪽을 확인할 수 없어 비교하지 않아요.");
    }

    @Test
    @DisplayName("주문이 하나도 없어도 오류 없이 계산 불가로 응답한다")
    void noOrders() {
        when(tendencyMapper.selectTradeSummariesByCustomerId(CUSTOMER_ID)).thenReturn(List.of());
        when(tendencyMapper.selectTradePeriodByCustomerId(CUSTOMER_ID))
                .thenReturn(new TradePeriodVO(0, null));

        StockTendencyResponseDTO r = service.getTendency(CUSTOMER_ID);

        assertThat(r.getTrading().isAvailable()).isFalse();
        assertThat(r.getTrading().getOrderCount()).isZero();
        assertThat(r.getComparison().isAvailable()).isFalse();
    }

    @Test
    @DisplayName("진단이 없거나 만료되면 설문 성향은 확인할 수 없고 비교하지 않는다")
    void surveyUnavailable() {
        when(assessmentService.getMine(CUSTOMER_ID))
                .thenReturn(InvestorAssessmentStatusResponseDTO.unassigned());

        StockTendencyResponseDTO r = service.getTendency(CUSTOMER_ID);

        assertThat(r.getSurvey().isAvailable()).isFalse();
        assertThat(r.getSurvey().getAllowedMinGrade()).isNull();
        assertThat(r.getTrading().isAvailable()).isTrue();
        assertThat(r.getComparison().isAvailable()).isFalse();
        assertThat(allText(r)).contains("설문 투자성향은 확인할 수 없어요.");
    }

    @Test
    @DisplayName("등급이 있는 종목의 매수가 없으면 위험등급은 계산하지 않는다")
    void noGradedBuy() {
        when(tendencyMapper.selectTradeSummariesByCustomerId(CUSTOMER_ID))
                .thenReturn(List.of(summary(3L, "기타", "5000000", 5, 0)));

        StockTendencyResponseDTO r = service.getTendency(CUSTOMER_ID);

        assertThat(r.getTrading().isAvailable()).isTrue();
        assertThat(r.getTrading().getAvgBuyGrade()).isNull();
        assertThat(r.getTrading().getGradeDistribution()).isEmpty();
        assertThat(r.getComparison().isAvailable()).isFalse();
        assertThat(allText(r)).contains("위험등급은 계산할 수 없어요.");
    }

    @Test
    @DisplayName("설명에는 권유·평가 표현이 없고 거래에 성향 이름표를 붙이지 않는다")
    void explanationHasNoAdvice() {
        StockTendencyResponseDTO r = service.getTendency(CUSTOMER_ID);

        String all = allText(r);
        for (String banned : StockPackageSentenceTest.BANNED) {
            assertThat(all).as("금지 표현 '%s'", banned).doesNotContain(banned);
        }
        // 거래 쪽 설명에는 성향 이름이 나오지 않는다(설문 성향 문장에만 나온다).
        String trading =
                r.getExplanation().stream()
                        .filter(b -> b.getMetric().startsWith("TRADING"))
                        .map(b -> b.getDefinition() + " " + String.join(" ", b.getFacts()))
                        .collect(Collectors.joining(" "));
        for (String label : List.of("공격투자형", "적극투자형", "위험중립형", "안정추구형", "안정형")) {
            assertThat(trading).doesNotContain(label);
        }
    }

    private static String allText(StockTendencyResponseDTO r) {
        return r.getExplanation().stream()
                .map(b -> b.getDefinition() + " " + String.join(" ", b.getFacts()))
                .collect(Collectors.joining(" "));
    }

    private static TradeProductSummaryVO summary(
            Long productId, String sector, String buyAmount, int buys, int sells) {
        return TradeProductSummaryVO.builder()
                .domesticProductId(productId)
                .sectorName(sector)
                .buyAmount(new BigDecimal(buyAmount))
                .buyCount(buys)
                .sellCount(sells)
                .build();
    }

    private static UsableRiskGradeDTO grade(Long productId, int grade) {
        return new UsableRiskGradeDTO(
                productId,
                "T" + productId,
                "종목" + productId,
                new BigDecimal("0.3"),
                grade,
                new BigDecimal("0.2"),
                LocalDate.of(2023, 11, 1),
                LocalDate.of(2026, 11, 2),
                1L,
                RiskDataStatus.CURRENT);
    }

    private static StockProductThemeVO theme(Long productId, String name) {
        return StockProductThemeVO.builder().domesticProductId(productId).themeName(name).build();
    }
}
