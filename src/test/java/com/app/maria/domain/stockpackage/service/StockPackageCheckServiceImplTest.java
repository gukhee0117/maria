package com.app.maria.domain.stockpackage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.domestic.type.Type;
import com.app.maria.domain.investorassessment.dto.response.InvestorAssessmentStatusResponseDTO;
import com.app.maria.domain.investorassessment.service.InvestorAssessmentService;
import com.app.maria.domain.risk.dto.UsableRiskGradeDTO;
import com.app.maria.domain.risk.service.RiskGradeService;
import com.app.maria.domain.risk.type.RiskDataStatus;
import com.app.maria.domain.stockpackage.dto.request.StockPackageCheckRequestDTO;
import com.app.maria.domain.stockpackage.dto.request.StockPackageCheckRequestDTO.Item;
import com.app.maria.domain.stockpackage.dto.response.StockPackageCheckResponseDTO;
import com.app.maria.domain.stockpackage.mapper.StockPackageMapper;
import com.app.maria.domain.stockpackage.type.StockPackageSentenceTest;
import com.app.maria.domain.stockpackage.vo.StockProductThemeVO;
import com.app.maria.domain.stockpackage.vo.StockProductVO;
import com.app.maria.global.client.risk.PythonPackageRiskClient;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
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
class StockPackageCheckServiceImplTest {
    private static final Long CUSTOMER_ID = 1L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 10, 0);

    @Mock private StockPackageMapper mapper;
    @Mock private InvestorAssessmentService assessmentService;
    @Mock private RiskGradeService riskGradeService;
    @Mock private PythonPackageRiskClient riskClient;
    @Mock private BusinessClockService clock;
    @InjectMocks private StockPackageCheckServiceImpl service;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient()
                .doCallRealMethod()
                .when(riskGradeService)
                .findUsableGrades(org.mockito.ArgumentMatchers.anyCollection());
        org.mockito.Mockito.doCallRealMethod().when(riskGradeService).findUsableGrades();
        when(clock.now()).thenReturn(NOW);
        // 중립형(4등급까지). 1번 종목 5등급, 2번 종목 2등급(성향 밖)
        when(assessmentService.getMine(CUSTOMER_ID))
                .thenReturn(
                        InvestorAssessmentStatusResponseDTO.of(
                                InvestorType.NEUTRAL,
                                NOW.minusMonths(1),
                                NOW.plusMonths(23),
                                false));
        when(riskGradeService.findUsable()).thenReturn(List.of(grade(1L, 5), grade(2L, 2)));
        when(mapper.selectProductsByIds(anyList()))
                .thenReturn(
                        List.of(
                                product(1L, "A전자", "005930", "전기전자"),
                                product(2L, "B소재", "000660", null)));
        when(mapper.selectThemesByProductIds(anyList()))
                .thenReturn(List.of(theme(1L, "반도체"), theme(2L, "2차전지"), theme(2L, "반도체")));
        when(riskClient.fetchVolatility(anyList()))
                .thenReturn(
                        new PythonPackageRiskClient.Volatility(
                                new BigDecimal("0.2215"),
                                OffsetDateTime.parse("2026-11-03T15:40:00+09:00")));
    }

    @Test
    @DisplayName("지표를 코드가 계산한다")
    void metrics() {
        StockPackageCheckResponseDTO r =
                service.check(CUSTOMER_ID, request(item(1L, "60"), item(2L, "40")));

        assertThat(r.getAvgRiskGrade()).isEqualByComparingTo("3.80");
        assertThat(r.getOutOfProfileCount()).isEqualTo(1);
        assertThat(r.getOutOfProfileNames()).containsExactly("B소재");
        assertThat(r.getHhi()).isEqualByComparingTo("5200");
        assertThat(r.getEqualWeightHhi()).isEqualByComparingTo("5000");
        assertThat(r.getTopItem().getName()).isEqualTo("A전자");
        assertThat(r.getTopItem().getWeight()).isEqualByComparingTo("60");
        assertThat(r.getContributions().get(0).getHhiSharePercent()).isEqualByComparingTo("69.2");
        assertThat(r.getContributions().get(1).getHhiSharePercent()).isEqualByComparingTo("30.8");
        assertThat(r.getThemes()).extracting(t -> t.getName()).containsExactly("반도체", "2차전지");
        assertThat(r.getThemes().get(0).getWeight()).isEqualByComparingTo("100");
        assertThat(r.getSectors()).extracting(s -> s.getName()).containsExactly("전기전자", "업종 없음");
        assertThat(r.getVolatilityPercent()).isEqualByComparingTo("22.2");
        assertThat(r.getUnavailableFields()).isEmpty();
        assertThat(r.getDisclaimer()).isEqualTo("투자 권유가 아닌 정보 제공이에요");
        assertThat(r.getCheckedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("risk에는 퍼센트가 아니라 합이 1인 비율로 보낸다")
    void sendsFractionWeights() {
        service.check(CUSTOMER_ID, request(item(1L, "60"), item(2L, "40")));

        ArgumentCaptor<List<PythonPackageRiskClient.Item>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(riskClient).fetchVolatility(captor.capture());
        assertThat(captor.getValue())
                .extracting(i -> i.ticker())
                .containsExactly("005930", "000660");
        assertThat(captor.getValue().get(0).weight()).isEqualByComparingTo("0.6");
        assertThat(captor.getValue().get(1).weight()).isEqualByComparingTo("0.4");
    }

    @Test
    @DisplayName("risk를 쓸 수 없으면 변동성만 계산 불가이고 나머지는 그대로다")
    void riskUnavailable() {
        when(riskClient.fetchVolatility(anyList()))
                .thenThrow(new AppException(ErrorType.RISK_UPSTREAM_UNAVAILABLE));

        StockPackageCheckResponseDTO r =
                service.check(CUSTOMER_ID, request(item(1L, "60"), item(2L, "40")));

        assertThat(r.getVolatilityPercent()).isNull();
        assertThat(r.getVolatilityAsOf()).isNull();
        assertThat(r.getUnavailableFields()).containsExactly("volatility");
        assertThat(r.getAvgRiskGrade()).isEqualByComparingTo("3.80");
        assertThat(allText(r)).contains("연 변동성은 지금 계산할 수 없어요.");
    }

    @Test
    @DisplayName("진단이 없거나 만료되면 성향 범위 밖 여부는 판단하지 않는다")
    void profileUnavailable() {
        when(assessmentService.getMine(CUSTOMER_ID))
                .thenReturn(InvestorAssessmentStatusResponseDTO.unassigned());

        StockPackageCheckResponseDTO r =
                service.check(CUSTOMER_ID, request(item(1L, "60"), item(2L, "40")));

        assertThat(r.isProfileAvailable()).isFalse();
        assertThat(r.getOutOfProfileCount()).isNull();
        assertThat(r.getOutOfProfileNames()).isNull();
        assertThat(r.getContributions()).allMatch(c -> c.getOutOfProfile() == null);
        assertThat(allText(r)).contains("내 성향 범위는 확인할 수 없어요.");
        assertThat(r.getAllowedMinGrade()).isNull();
    }

    @Test
    @DisplayName("설명은 문장은행 문장에 계산한 값만 끼워 만들고 권유·평가 표현이 없다")
    void explanationFromSentenceBank() {
        StockPackageCheckResponseDTO r =
                service.check(CUSTOMER_ID, request(item(1L, "60"), item(2L, "40")));

        assertThat(r.getExplanation())
                .extracting(b -> b.getMetric())
                .containsExactly(
                        "AVG_GRADE",
                        "PROFILE_RANGE",
                        "VOLATILITY",
                        "CONCENTRATION",
                        "TOP_ITEM",
                        "THEME",
                        "SECTOR");
        String all = allText(r);
        assertThat(all)
                .contains("이 패키지의 위험등급 가중평균은 3.80등급이에요.")
                .contains("내 투자성향 범위를 벗어난 종목은 1개예요. 해당 종목은 B소재예요.")
                .contains("내 투자성향에서 허용하는 가장 높은 위험은 4등급이에요.")
                .contains("가중평균 3.80등급은 이 범위를 벗어나 있어요.")
                .contains("이 패키지의 연 변동성은 22.2%예요.")
                .contains("이 패키지의 종목 집중도는 5200이에요.")
                .contains("이 패키지는 그보다 큰 값이에요.")
                .contains("A전자 69.2%, B소재 30.8%")
                .contains("A전자의 비중이 60%로 가장 커요.")
                .contains("비중의 합이 가장 큰 테마는 반도체로 100%예요.")
                .contains("비중의 합이 가장 큰 업종은 전기전자로 60%예요.");
        for (String banned : StockPackageSentenceTest.BANNED) {
            if (banned.equals("수익")) {
                continue; // 변동성이 예측값이 아니라는 고정 문장에만 들어 있다.
            }
            assertThat(all).as("금지 표현 '%s'", banned).doesNotContain(banned);
        }
    }

    private static String allText(StockPackageCheckResponseDTO r) {
        return r.getExplanation().stream()
                .map(b -> b.getDefinition() + " " + String.join(" ", b.getFacts()))
                .collect(java.util.stream.Collectors.joining(" "));
    }

    @Test
    @DisplayName("잘못된 구성은 거부한다")
    void invalid() {
        assertError(request(item(1L, "60")), ErrorType.STOCK_PACKAGE_WEIGHT_SUM_INVALID);
        assertError(
                request(item(1L, "50"), item(1L, "50")), ErrorType.STOCK_PACKAGE_ITEM_DUPLICATED);

        when(riskGradeService.findUsable()).thenReturn(List.of(grade(1L, 5)));
        assertError(
                request(item(1L, "60"), item(2L, "40")), ErrorType.STOCK_PACKAGE_GRADE_UNAVAILABLE);

        when(riskGradeService.findUsable()).thenReturn(List.of(grade(1L, 5), grade(2L, 2)));
        when(mapper.selectProductsByIds(anyList()))
                .thenReturn(List.of(product(1L, "A전자", "005930", null)));
        assertError(request(item(1L, "60"), item(2L, "40")), ErrorType.DOMESTIC_PRODUCT_NOT_FOUND);
    }

    private void assertError(StockPackageCheckRequestDTO request, ErrorType expected) {
        assertThatThrownBy(() -> service.check(CUSTOMER_ID, request))
                .isInstanceOfSatisfying(
                        AppException.class, e -> assertThat(e.getErrorType()).isEqualTo(expected));
    }

    private static StockPackageCheckRequestDTO request(Item... items) {
        return new StockPackageCheckRequestDTO(List.of(items));
    }

    private static Item item(Long productId, String weight) {
        return new Item(productId, new BigDecimal(weight));
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

    private static StockProductVO product(Long id, String name, String ticker, String sector) {
        return StockProductVO.builder()
                .domesticProductId(id)
                .ticker(ticker)
                .name(name)
                .type(Type.STOCK)
                .sectorName(sector)
                .build();
    }

    private static StockProductThemeVO theme(Long productId, String name) {
        return StockProductThemeVO.builder().domesticProductId(productId).themeName(name).build();
    }
}
