package com.app.maria.domain.stockpackage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.domestic.service.DomesticPurchaseEligibilityService;
import com.app.maria.domain.domestic.type.Type;
import com.app.maria.domain.investorassessment.dto.response.InvestorAssessmentStatusResponseDTO;
import com.app.maria.domain.investorassessment.service.InvestorAssessmentService;
import com.app.maria.domain.risk.dto.UsableRiskGradeDTO;
import com.app.maria.domain.risk.service.RiskGradeService;
import com.app.maria.domain.risk.type.RiskDataStatus;
import com.app.maria.domain.stockpackage.dto.request.StockCandidateRequestDTO;
import com.app.maria.domain.stockpackage.dto.response.StockCandidateResponseDTO;
import com.app.maria.domain.stockpackage.mapper.StockCandidateMapper;
import com.app.maria.domain.stockpackage.mapper.StockPackageMapper;
import com.app.maria.domain.stockpackage.type.CandidateSort;
import com.app.maria.domain.stockpackage.type.SortDirection;
import com.app.maria.domain.stockpackage.type.StockPackageSentenceTest;
import com.app.maria.domain.stockpackage.vo.StockCandidateProductVO;
import com.app.maria.domain.stockpackage.vo.StockProductThemeVO;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
class StockCandidateServiceImplTest {
    private static final Long CUSTOMER_ID = 1L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 10, 0);

    @Mock private StockCandidateMapper candidateMapper;
    @Mock private StockPackageMapper stockPackageMapper;
    @Mock private InvestorAssessmentService assessmentService;
    @Mock private RiskGradeService riskGradeService;
    @Mock private DomesticPurchaseEligibilityService eligibilityService;
    @Mock private BusinessClockService clock;
    @InjectMocks private StockCandidateServiceImpl service;

    @BeforeEach
    void setUp() {
        when(clock.now()).thenReturn(NOW);
        // 위험중립형(4등급까지)
        when(assessmentService.getMine(CUSTOMER_ID))
                .thenReturn(
                        InvestorAssessmentStatusResponseDTO.of(
                                InvestorType.NEUTRAL,
                                NOW.minusMonths(1),
                                NOW.plusMonths(23),
                                false));
        // A전자 5등급, B소재 2등급, C펀드 4등급, D신규는 등급 없음, E불가는 RIA에서 살 수 없는 펀드
        when(candidateMapper.selectCandidateProducts(anyList(), anyList(), any()))
                .thenReturn(
                        List.of(
                                product(1L, "A전자", "005930", Type.STOCK, "전기전자", null),
                                product(2L, "B소재", "000660", Type.STOCK, null, null),
                                product(3L, "C펀드", "900020", Type.FUND, null, "90"),
                                product(4L, "D신규", "123456", Type.STOCK, null, null),
                                product(5L, "E불가", "900040", Type.FUND, null, "40")));
        when(eligibilityService.isPurchasable(any(Type.class), any(), any())).thenReturn(true);
        when(eligibilityService.isPurchasable(eq(Type.FUND), eq(new BigDecimal("40")), any()))
                .thenReturn(false);
        when(riskGradeService.findUsable())
                .thenReturn(
                        List.of(grade(1L, 5, "0.20"), grade(2L, 2, "0.45"), grade(3L, 4, "0.10")));
        when(stockPackageMapper.selectThemesByProductIds(anyList()))
                .thenReturn(List.of(theme(1L, "반도체"), theme(1L, "배당"), theme(3L, "배당")));
    }

    @Test
    @DisplayName("기본은 내 성향 범위 안의 상품만 가나다순으로 보여주고 RIA에서 살 수 없는 상품은 뺀다")
    void defaultRestrictsToProfile() {
        StockCandidateResponseDTO r = service.search(CUSTOMER_ID, request(false, null, null));

        assertThat(names(r)).containsExactly("A전자", "C펀드");
        assertThat(r.getTotalCount()).isEqualTo(2);
        assertThat(r.getItems().get(0).getThemes()).containsExactly("반도체", "배당");
        assertThat(r.getItems().get(0).getOutOfProfile()).isFalse();
        assertThat(r.getItems().get(0).getVolatilityPercent()).isEqualByComparingTo("20.0");
        assertThat(r.isProfileAvailable()).isTrue();
        assertThat(r.getAllowedMinGrade()).isEqualTo(4);
        assertThat(r.getSort()).isEqualTo("NAME");
        assertThat(r.getDirection()).isEqualTo("ASC");
    }

    @Test
    @DisplayName("범위 밖 포함을 켜면 범위 안 → 범위 밖 → 등급 없음 순서로 보이고, 등급 없는 상품은 담을 수 없다고 표시한다")
    void includeOutOfProfile() {
        StockCandidateResponseDTO r = service.search(CUSTOMER_ID, request(true, null, null));

        assertThat(names(r)).containsExactly("A전자", "C펀드", "B소재", "D신규");
        var b = r.getItems().get(2);
        assertThat(b.getOutOfProfile()).isTrue();
        assertThat(b.isSelectable()).isTrue();
        var d = r.getItems().get(3);
        assertThat(d.getRiskGrade()).isNull();
        assertThat(d.getOutOfProfile()).isNull();
        assertThat(d.isSelectable()).isFalse();
        assertThat(d.getUnselectableReason()).isEqualTo("위험등급이 아직 계산되지 않아 담을 수 없어요.");
    }

    @Test
    @DisplayName("위험등급 범위를 주면 그 범위의 상품만 남고 등급 없는 상품은 빠진다")
    void gradeRange() {
        StockCandidateResponseDTO r = service.search(CUSTOMER_ID, request(true, 1, 2));

        assertThat(names(r)).containsExactly("B소재");
    }

    @Test
    @DisplayName("위험등급 범위의 시작이 끝보다 크면 거부한다")
    void invalidRange() {
        assertThatThrownBy(() -> service.search(CUSTOMER_ID, request(true, 5, 2)))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(
                                                ErrorType.STOCK_PACKAGE_CANDIDATE_FILTER_INVALID));
    }

    @Test
    @DisplayName("위험등급순·변동성순 정렬은 값 없는 상품을 항상 맨 뒤에 둔다")
    void sorts() {
        assertThat(
                        names(
                                service.search(
                                        CUSTOMER_ID,
                                        request(
                                                true,
                                                null,
                                                null,
                                                CandidateSort.RISK_GRADE,
                                                SortDirection.ASC))))
                .containsExactly("B소재", "C펀드", "A전자", "D신규");
        assertThat(
                        names(
                                service.search(
                                        CUSTOMER_ID,
                                        request(
                                                true,
                                                null,
                                                null,
                                                CandidateSort.RISK_GRADE,
                                                SortDirection.DESC))))
                .containsExactly("A전자", "C펀드", "B소재", "D신규");
        assertThat(
                        names(
                                service.search(
                                        CUSTOMER_ID,
                                        request(
                                                true,
                                                null,
                                                null,
                                                CandidateSort.VOLATILITY,
                                                SortDirection.ASC))))
                .containsExactly("C펀드", "A전자", "B소재", "D신규");
        assertThat(
                        names(
                                service.search(
                                        CUSTOMER_ID,
                                        request(
                                                true,
                                                null,
                                                null,
                                                CandidateSort.NAME,
                                                SortDirection.DESC))))
                // 종목명순은 범위 안(C펀드·A전자) → 범위 밖(B소재) → 등급 없음(D신규)으로 묶은 뒤 그 안에서 역순이다
                .containsExactly("C펀드", "A전자", "B소재", "D신규");
    }

    @Test
    @DisplayName("범위 밖도 함께 보는 종목명순 목록은 범위 안 상품을 먼저 둔다고 알린다")
    void profileFirstSummary() {
        StockCandidateResponseDTO all = service.search(CUSTOMER_ID, request(true, null, null));
        StockCandidateResponseDTO only = service.search(CUSTOMER_ID, request(false, null, null));

        assertThat(all.getSummary()).contains("내 투자성향 범위 안 상품을 먼저 두고, 범위 밖 상품에는 표시를 붙여요.");
        assertThat(only.getSummary()).noneMatch(s -> s.contains("먼저 두고"));
    }

    @Test
    @DisplayName("진단이 없거나 만료되면 성향 범위를 적용하지 않고 범위 밖 여부는 판단하지 않는다")
    void profileUnknown() {
        when(assessmentService.getMine(CUSTOMER_ID))
                .thenReturn(InvestorAssessmentStatusResponseDTO.unassigned());

        StockCandidateResponseDTO r = service.search(CUSTOMER_ID, request(false, null, null));

        assertThat(names(r)).containsExactly("A전자", "B소재", "C펀드", "D신규");
        assertThat(r.isProfileAvailable()).isFalse();
        assertThat(r.getAllowedMinGrade()).isNull();
        assertThat(r.getItems()).allMatch(i -> i.getOutOfProfile() == null);
        assertThat(String.join(" ", r.getSummary())).contains("성향 범위를 적용하지 않았어요.");
    }

    @Test
    @DisplayName("200개를 넘으면 앞의 200개만 주고 전체 개수와 잘렸음을 알린다")
    void truncation() {
        List<StockCandidateProductVO> many = new ArrayList<>();
        List<UsableRiskGradeDTO> grades = new ArrayList<>();
        for (long i = 1; i <= 250; i++) {
            many.add(
                    product(
                            i,
                            String.format("종목%03d", i),
                            String.format("%06d", i),
                            Type.STOCK,
                            null,
                            null));
            grades.add(grade(i, 5, "0.20"));
        }
        when(candidateMapper.selectCandidateProducts(anyList(), anyList(), any())).thenReturn(many);
        when(riskGradeService.findUsable()).thenReturn(grades);

        StockCandidateResponseDTO r = service.search(CUSTOMER_ID, request(false, null, null));

        assertThat(r.getItems()).hasSize(200);
        assertThat(r.getTotalCount()).isEqualTo(250);
        assertThat(r.isTruncated()).isTrue();
        assertThat(String.join(" ", r.getSummary()))
                .contains("조건에 맞는 상품은 모두 250개이고, 앞의 200개만 보여줘요.");
    }

    @Test
    @DisplayName("조건에 맞는 상품이 없으면 없다고만 알린다")
    void empty() {
        when(candidateMapper.selectCandidateProducts(anyList(), anyList(), any()))
                .thenReturn(List.of());

        StockCandidateResponseDTO r = service.search(CUSTOMER_ID, request(false, null, null));

        assertThat(r.getItems()).isEmpty();
        assertThat(r.getSummary().get(0)).isEqualTo("조건에 맞는 상품이 없어요.");
    }

    @Test
    @DisplayName("검색어의 LIKE 특수문자는 이스케이프해서 넘긴다")
    void keywordEscaped() {
        service.search(
                CUSTOMER_ID,
                new StockCandidateRequestDTO(
                        null, null, null, null, " 50%_a\\ ", false, null, null));

        ArgumentCaptor<String> keyword = ArgumentCaptor.forClass(String.class);
        verify(candidateMapper).selectCandidateProducts(anyList(), anyList(), keyword.capture());
        assertThat(keyword.getValue()).isEqualTo("50\\%\\_a\\\\");
    }

    @Test
    @DisplayName("안내 문장에 권유·평가 표현이 없고 순위를 매긴 것이 아니라고 밝힌다")
    void summaryHasNoAdvice() {
        StockCandidateResponseDTO r = service.search(CUSTOMER_ID, request(true, null, null));

        String all = String.join(" ", r.getSummary());
        for (String banned : StockPackageSentenceTest.BANNED) {
            assertThat(all).as("금지 표현 '%s'", banned).doesNotContain(banned);
        }
        assertThat(all).contains("순위를 매긴 것이 아니에요.");
        assertThat(r.getDisclaimer()).isEqualTo("투자 권유가 아닌 정보 제공이에요");
    }

    private static List<String> names(StockCandidateResponseDTO r) {
        return r.getItems().stream().map(i -> i.getName()).toList();
    }

    private static StockCandidateRequestDTO request(
            boolean includeOutOfProfile, Integer min, Integer max) {
        return request(includeOutOfProfile, min, max, null, null);
    }

    private static StockCandidateRequestDTO request(
            boolean includeOutOfProfile,
            Integer min,
            Integer max,
            CandidateSort sort,
            SortDirection direction) {
        return new StockCandidateRequestDTO(
                Set.of(), List.of(), min, max, null, includeOutOfProfile, sort, direction);
    }

    private static StockCandidateProductVO product(
            Long id, String name, String ticker, Type type, String sector, String ratio) {
        return StockCandidateProductVO.builder()
                .domesticProductId(id)
                .ticker(ticker)
                .name(name)
                .market("KOSPI")
                .type(type)
                .sectorName(sector)
                .domesticStockRatio(ratio == null ? null : new BigDecimal(ratio))
                .build();
    }

    private static UsableRiskGradeDTO grade(Long productId, int grade, String volatility) {
        return new UsableRiskGradeDTO(
                productId,
                "T" + productId,
                "종목" + productId,
                new BigDecimal("0.3"),
                grade,
                new BigDecimal(volatility),
                LocalDate.of(2023, 11, 1),
                LocalDate.of(2026, 11, 2),
                1L,
                RiskDataStatus.CURRENT);
    }

    private static StockProductThemeVO theme(Long productId, String name) {
        return StockProductThemeVO.builder().domesticProductId(productId).themeName(name).build();
    }
}
