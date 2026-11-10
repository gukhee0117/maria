package com.app.maria.domain.stockpackage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.domestic.type.Type;
import com.app.maria.domain.stockpackage.ai.AiBusyException;
import com.app.maria.domain.stockpackage.ai.StockRecommendationAnswerer;
import com.app.maria.domain.stockpackage.dto.request.StockCandidateRequestDTO;
import com.app.maria.domain.stockpackage.dto.request.StockRecommendationAskRequestDTO;
import com.app.maria.domain.stockpackage.dto.response.StockCandidateResponseDTO;
import com.app.maria.domain.stockpackage.dto.response.StockPackageCheckResponseDTO;
import com.app.maria.domain.stockpackage.dto.response.StockRecommendationAskResponseDTO;
import com.app.maria.domain.stockpackage.dto.response.StockRecommendationResponseDTO;
import com.app.maria.domain.stockpackage.dto.response.StockTendencyResponseDTO;
import com.app.maria.domain.stockpackage.mapper.StockOrderMapper;
import com.app.maria.domain.stockpackage.vo.StockBalanceVO;
import com.app.maria.domain.stockpackage.vo.StockCandidateListVO;
import com.app.maria.domain.stockpackage.vo.StockCandidateListVO.Row;
import com.app.maria.global.clock.service.BusinessClockService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
class StockRecommendationServiceImplTest {
    private static final Long CUSTOMER_ID = 1L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 6, 10, 0);

    @Mock private StockCandidateService candidateService;
    @Mock private StockRecommendationAnswerer answerer;
    @Mock private BusinessClockService clock;
    @Mock private StockTendencyService tendencyService;
    @Mock private AccountMapper accountMapper;
    @Mock private StockOrderMapper stockOrderMapper;
    @InjectMocks private StockRecommendationServiceImpl service;

    /** 거래 경향: 체결 매수 buyCount건, 평균 매수 등급 avg(null이면 계산 전), 많이 산 업종·테마. */
    private void trading(int buyCount, BigDecimal avg, String topSector, String topTheme) {
        StockTendencyResponseDTO.TradingResponse tr =
                mock(StockTendencyResponseDTO.TradingResponse.class);
        when(tr.isAvailable()).thenReturn(avg != null);
        when(tr.getMinBuyCount()).thenReturn(5);
        when(tr.getBuyCount()).thenReturn(buyCount);
        when(tr.getAvgBuyGrade()).thenReturn(avg);
        if (topSector != null) {
            StockPackageCheckResponseDTO.WeightedResponse w =
                    mock(StockPackageCheckResponseDTO.WeightedResponse.class);
            when(w.getName()).thenReturn(topSector);
            when(tr.getTopSector()).thenReturn(w);
        }
        if (topTheme != null) {
            StockPackageCheckResponseDTO.WeightedResponse w =
                    mock(StockPackageCheckResponseDTO.WeightedResponse.class);
            when(w.getName()).thenReturn(topTheme);
            when(tr.getTopTheme()).thenReturn(w);
        }
        StockTendencyResponseDTO dto = mock(StockTendencyResponseDTO.class);
        when(dto.getTrading()).thenReturn(tr);
        when(tendencyService.getTendency(any())).thenReturn(dto);
    }

    /** 고객 계좌 전체에서 지금 잔량이 있는(수량 > 0) 상품. selectHeldBalancesByAccount는 잔량이 있는 행만 준다. */
    private void holding(Long... productIds) {
        when(accountMapper.selectByCustomerId(any()))
                .thenReturn(Optional.of(AccountDTO.builder().accountId(10L).build()));
        List<StockBalanceVO> rows = new ArrayList<>();
        for (Long id : productIds) {
            rows.add(
                    StockBalanceVO.builder()
                            .accountId(10L)
                            .domesticProductId(id)
                            .qty(BigDecimal.TEN)
                            .build());
        }
        when(stockOrderMapper.selectHeldBalancesByAccount(10L)).thenReturn(rows);
    }

    @BeforeEach
    void setUp() {
        when(clock.now()).thenReturn(NOW);
        trading(0, null, null, null); // 기본: 거래 데이터 없음
        holding(); // 기본: 계좌는 있고 보유 없음
        candidates(true, 2); // 기본: 진단 유효(허용 2~6등급), 후보 없음
    }

    private List<String> tickers(StockRecommendationResponseDTO res) {
        return res.getItems().stream()
                .map(StockRecommendationResponseDTO.ItemResponse::getTicker)
                .toList();
    }

    private void candidates(boolean profileAvailable, Integer minGrade, Row... rows) {
        when(candidateService.search(any(), any(StockCandidateRequestDTO.class)))
                .thenReturn(
                        new StockCandidateResponseDTO(
                                StockCandidateListVO.builder()
                                        .items(List.of(rows))
                                        .totalCount(rows.length)
                                        .profileAvailable(profileAvailable)
                                        .allowedMinGrade(minGrade)
                                        .sort("NAME")
                                        .direction("ASC")
                                        .summary(List.of())
                                        .disclaimer("d")
                                        .checkedAt(NOW)
                                        .build()));
    }

    private static Row row(
            long id, String name, String ticker, Type type, String sector, int grade, boolean ok) {
        return new Row(
                id,
                ticker,
                name,
                "KOSPI",
                type,
                sector,
                List.of(),
                ok ? grade : null,
                ok ? new BigDecimal("21.5") : null,
                false,
                ok,
                null);
    }

    @Test
    @DisplayName("진단이 없으면 빈 목록과 profileAvailable=false를 준다")
    void noProfile() {
        candidates(false, null, row(1, "A", "000001", Type.STOCK, "전기", 2, true));

        StockRecommendationResponseDTO res = service.recommend(CUSTOMER_ID, "ALL", 3, "SURVEY");

        assertThat(res.isProfileAvailable()).isFalse();
        assertThat(res.getItems()).isEmpty();
    }

    @Test
    @DisplayName("등급이 없거나 고를 수 없는 상품은 빼고, 업종이 다른 상품을 먼저 채운다")
    void picksDistinctSectorsAndSkipsUnselectable() {
        candidates(
                true,
                2,
                row(1, "A전자", "000001", Type.STOCK, "전기전자", 2, true),
                row(2, "B전자", "000002", Type.STOCK, "전기전자", 2, true),
                row(3, "C화학", "000003", Type.STOCK, "화학", 2, true),
                row(4, "D신규", "000004", Type.STOCK, "금융", 0, false));

        StockRecommendationResponseDTO res = service.recommend(CUSTOMER_ID, "ALL", 2, "SURVEY");

        assertThat(res.getItems()).hasSize(2);
        assertThat(res.getItems())
                .extracting(StockRecommendationResponseDTO.ItemResponse::getSectorName)
                .containsExactlyInAnyOrder("전기전자", "화학");
        assertThat(res.getItems())
                .extracting(StockRecommendationResponseDTO.ItemResponse::getTicker)
                .doesNotContain("000004");
        assertThat(res.getItems().get(0).getReasons()).isNotEmpty();
    }

    @Test
    @DisplayName("성향이 허용하는 가장 높은 위험에 가까운 등급을 먼저 담는다")
    void closerToProfileFirst() {
        candidates(
                true,
                2,
                row(1, "A", "000001", Type.STOCK, "가", 5, true),
                row(2, "B", "000002", Type.STOCK, "나", 2, true));

        StockRecommendationResponseDTO res = service.recommend(CUSTOMER_ID, "ALL", 1, "SURVEY");

        assertThat(res.getItems().get(0).getTicker()).isEqualTo("000002");
    }

    @Test
    @DisplayName("거래 기준: 체결 매수가 모자라면 빈 목록과 basisAvailable=false, 건수를 준다")
    void tradingNotEnoughBuys() {
        trading(2, null, null, null);

        StockRecommendationResponseDTO res = service.recommend(CUSTOMER_ID, "ALL", 3, "TRADING");

        assertThat(res.getBasis()).isEqualTo("TRADING");
        assertThat(res.isBasisAvailable()).isFalse();
        assertThat(res.getItems()).isEmpty();
        assertThat(res.getTradeBuyCount()).isEqualTo(2);
        assertThat(res.getMinBuyCount()).isEqualTo(5);
        assertThat(res.getSubtitle()).contains("5건");
    }

    @Test
    @DisplayName("거래 기준: 평균 매수 등급 앞뒤 1등급만, 많이 산 업종 우선, 보유 중인 종목과 범위 밖 등급은 뺀다")
    void tradingPicksNearAverageAndSkipsHeld() {
        trading(8, new BigDecimal("3.2"), "화학", null);
        holding(4L);
        when(candidateService.search(any(), any(StockCandidateRequestDTO.class)))
                .thenReturn(
                        new StockCandidateResponseDTO(
                                StockCandidateListVO.builder()
                                        .items(
                                                List.of(
                                                        row(
                                                                1,
                                                                "A전자",
                                                                "000001",
                                                                Type.STOCK,
                                                                "전기",
                                                                3,
                                                                true),
                                                        row(
                                                                2,
                                                                "B화학",
                                                                "000002",
                                                                Type.STOCK,
                                                                "화학",
                                                                4,
                                                                true),
                                                        row(
                                                                3,
                                                                "C소재",
                                                                "000003",
                                                                Type.STOCK,
                                                                "소재",
                                                                6,
                                                                true),
                                                        row(
                                                                4,
                                                                "D화학",
                                                                "000004",
                                                                Type.STOCK,
                                                                "화학",
                                                                3,
                                                                true),
                                                        row(
                                                                5,
                                                                "E신규",
                                                                "000005",
                                                                Type.STOCK,
                                                                "금융",
                                                                3,
                                                                false)))
                                        .totalCount(5)
                                        .profileAvailable(true)
                                        .allowedMinGrade(2)
                                        .sort("NAME")
                                        .direction("ASC")
                                        .summary(List.of())
                                        .disclaimer("d")
                                        .checkedAt(NOW)
                                        .build()));

        StockRecommendationResponseDTO res = service.recommend(CUSTOMER_ID, "ALL", 3, "TRADING");

        // 평균 3.2 → 2~4등급. C소재(6등급)·E신규(등급 없음)·보유 중인 D화학(4번)은 빠지고, 화학 업종 B가 먼저다
        assertThat(res.isBasisAvailable()).isTrue();
        assertThat(res.getItems())
                .extracting(StockRecommendationResponseDTO.ItemResponse::getTicker)
                .containsExactly("000002", "000001");
        assertThat(res.getItems().get(0).getReasons()).anyMatch(r -> r.contains("가장 많이 산 업종(화학)"));
        assertThat(res.getBasisFacts()).anyMatch(f -> f.contains("3.2등급"));
    }

    private static Row rowT(long id, String ticker, String sector, int grade, List<String> themes) {
        return new Row(
                id,
                ticker,
                "종목" + id,
                "KOSPI",
                Type.STOCK,
                sector,
                themes,
                grade,
                new BigDecimal("21.5"),
                false,
                true,
                null);
    }

    @Test
    @DisplayName("(a) 목표 등급과 범위가 같으면 설문 기준과 거래 기준의 결과가 같다")
    void sameTargetSameResult() {
        // 허용 5~6등급, 평균 5등급 → 두 기준 모두 목표 5, 범위 5~6
        assertThat(StockRecommendationServiceImpl.tradingTarget(new BigDecimal("5"), 5))
                .isEqualTo(
                        new StockRecommendationServiceImpl.Target(
                                BigDecimal.valueOf(5), 5, 6, false));
        candidates(
                true,
                5,
                row(1, "A", "000001", Type.STOCK, "가", 5, true),
                row(2, "B", "000002", Type.STOCK, "나", 6, true),
                row(3, "C", "000003", Type.STOCK, "다", 5, true),
                row(4, "D", "000004", Type.STOCK, "라", 6, true),
                row(5, "E", "000005", Type.STOCK, "마", 4, true));
        trading(8, new BigDecimal("5"), null, null);

        StockRecommendationResponseDTO survey = service.recommend(CUSTOMER_ID, "ALL", 4, "SURVEY");
        StockRecommendationResponseDTO trading =
                service.recommend(CUSTOMER_ID, "ALL", 4, "TRADING");

        assertThat(tickers(survey)).isNotEmpty().doesNotContain("000005");
        assertThat(tickers(trading)).isEqualTo(tickers(survey));
        assertThat(survey.getGradeFrom()).isEqualTo(trading.getGradeFrom()).isEqualTo(5);
        assertThat(survey.getGradeTo()).isEqualTo(trading.getGradeTo()).isEqualTo(6);
        assertThat(survey.isTargetAdjusted()).isFalse();
        assertThat(trading.isTargetAdjusted()).isFalse();
    }

    @Test
    @DisplayName("(b) 적극투자형(2~6) + 평균 1.21등급: 1등급 종목이 나오지 않고 보정 플래그가 true다")
    void tradingAverageOutsideAllowedRangeIsAdjusted() {
        candidates(
                true,
                2,
                row(1, "A", "000001", Type.STOCK, "가", 1, true),
                row(2, "B", "000002", Type.STOCK, "나", 2, true),
                row(3, "C", "000003", Type.STOCK, "다", 3, true),
                row(4, "D", "000004", Type.STOCK, "라", 4, true));
        trading(9, new BigDecimal("1.21"), null, null);

        StockRecommendationResponseDTO res = service.recommend(CUSTOMER_ID, "ALL", 6, "TRADING");

        assertThat(res.isTargetAdjusted()).isTrue();
        assertThat(res.getTargetGrade()).isEqualByComparingTo("2");
        assertThat(res.getGradeFrom()).isEqualTo(2);
        assertThat(res.getGradeTo()).isEqualTo(3);
        assertThat(tickers(res)).containsExactly("000002", "000003");
        assertThat(res.getItems()).noneMatch(i -> i.getRiskGrade() == 1);
        assertThat(res.getItems())
                .noneMatch(StockRecommendationResponseDTO.ItemResponse::isOutOfProfile);
        assertThat(res.getBasisFacts()).anyMatch(f -> f.contains("허용 범위 경계"));
    }

    @Test
    @DisplayName("(c) 이미 보유한 종목은 설문 기준과 거래 기준 모두 추천하지 않는다")
    void heldProductsAreExcludedInBothBases() {
        candidates(
                true,
                2,
                row(1, "A", "000001", Type.STOCK, "가", 2, true),
                row(2, "B", "000002", Type.STOCK, "나", 3, true),
                row(3, "C", "000003", Type.STOCK, "다", 3, true));
        trading(8, new BigDecimal("3"), null, null);
        holding(1L, 2L);

        assertThat(tickers(service.recommend(CUSTOMER_ID, "ALL", 6, "SURVEY")))
                .containsExactly("000003");
        assertThat(tickers(service.recommend(CUSTOMER_ID, "ALL", 6, "TRADING")))
                .containsExactly("000003");
    }

    @Test
    @DisplayName("(d) 전량 매도한 종목(보유 0, 매수 이력 있음)은 두 기준 모두 후보에 다시 들어갈 수 있다")
    void soldOutProductCanBeRecommendedAgain() {
        // 종목 1은 예전에 샀다가 전부 팔았다: 매수 이력은 있지만 잔량 행이 없어 보유 목록에 없다
        candidates(
                true,
                2,
                row(1, "A", "000001", Type.STOCK, "가", 3, true),
                row(2, "B", "000002", Type.STOCK, "나", 3, true));
        trading(8, new BigDecimal("3"), "가", null);
        holding(); // 잔량 있는 보유 없음

        StockRecommendationResponseDTO survey = service.recommend(CUSTOMER_ID, "ALL", 6, "SURVEY");
        StockRecommendationResponseDTO trading =
                service.recommend(CUSTOMER_ID, "ALL", 6, "TRADING");

        assertThat(tickers(survey)).contains("000001");
        assertThat(tickers(trading)).contains("000001");
        // 많이 산 업종(가) 우선은 그대로 적용된다
        assertThat(tickers(trading).get(0)).isEqualTo("000001");
    }

    @Test
    @DisplayName("설문 기준에도 많이 산 업종·테마 우선을 적용하고, 거래 데이터가 없으면 적용하지 않는다")
    void surveyUsesTasteOnlyWithTradeData() {
        candidates(
                true,
                2,
                rowT(1, "000001", "가", 2, List.of()),
                rowT(2, "000002", "나", 2, List.of("반도체")),
                rowT(3, "000003", "다", 2, List.of()));
        trading(8, new BigDecimal("2"), "다", "반도체");

        // 업종·테마 모두 같음 0, 업종만 1, 테마만 2, 둘 다 아님 3 → 다(1), 나(2), 가(3)
        StockRecommendationResponseDTO withTaste =
                service.recommend(CUSTOMER_ID, "ALL", 3, "SURVEY");
        assertThat(tickers(withTaste)).containsExactly("000003", "000002", "000001");
        // 일치한 테마 이름은 테마가 같은 종목(2번)에만 담기고, 나머지는 null이다
        assertThat(withTaste.getItems())
                .extracting(
                        StockRecommendationResponseDTO.ItemResponse::getTicker,
                        StockRecommendationResponseDTO.ItemResponse::getMatchedTheme)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("000003", null),
                        org.assertj.core.api.Assertions.tuple("000002", "반도체"),
                        org.assertj.core.api.Assertions.tuple("000001", null));

        trading(0, null, null, null); // 거래 데이터 없음 → 우선 규칙 없이 동작
        StockRecommendationResponseDTO noTaste = service.recommend(CUSTOMER_ID, "ALL", 3, "SURVEY");
        assertThat(noTaste.getItems()).hasSize(3);
        assertThat(noTaste.getItems())
                .noneMatch(StockRecommendationResponseDTO.ItemResponse::isSectorMatched);
        assertThat(noTaste.getItems())
                .noneMatch(StockRecommendationResponseDTO.ItemResponse::isThemeMatched);
        assertThat(noTaste.getItems()).allMatch(i -> i.getMatchedTheme() == null);
    }

    @Test
    @DisplayName("계좌가 없는 고객은 보유 종목 없음으로 보고 오류 없이 추천한다")
    void noAccountMeansNoHoldings() {
        when(accountMapper.selectByCustomerId(any())).thenReturn(Optional.empty());
        candidates(true, 2, row(1, "A", "000001", Type.STOCK, "가", 2, true));
        trading(8, new BigDecimal("2"), null, null);

        assertThat(tickers(service.recommend(CUSTOMER_ID, "ALL", 3, "SURVEY")))
                .containsExactly("000001");
        assertThat(tickers(service.recommend(CUSTOMER_ID, "ALL", 3, "TRADING")))
                .containsExactly("000001");
        verify(stockOrderMapper, never()).selectHeldBalancesByAccount(any());
    }

    @Test
    @DisplayName("응답에 업종·테마 일치 필드가 담긴다")
    void responseCarriesTagFields() {
        candidates(true, 2, rowT(1, "000001", "가", 2, List.of("반도체")));
        trading(8, new BigDecimal("2"), "가", "반도체");

        StockRecommendationResponseDTO.ItemResponse item =
                service.recommend(CUSTOMER_ID, "ALL", 3, "SURVEY").getItems().get(0);

        assertThat(item.isSectorMatched()).isTrue();
        assertThat(item.isThemeMatched()).isTrue();
        assertThat(item.getMatchedTheme()).isEqualTo("반도체");
        assertThat(item.getReasons()).anyMatch(r -> r.contains("가장 많이 산 업종(가)"));
    }

    @Test
    @DisplayName("거래 기준: 설문 진단이 없거나 만료됐으면 basisAvailable=false, 빈 목록, 진단 안내를 준다")
    void tradingWithoutValidSurveyIsUnavailable() {
        candidates(false, null, row(1, "A", "000001", Type.STOCK, "가", 3, true));
        trading(9, new BigDecimal("3"), "가", null); // 거래는 충분해도 허용 범위를 모르면 추천하지 않는다

        StockRecommendationResponseDTO res = service.recommend(CUSTOMER_ID, "ALL", 3, "TRADING");

        assertThat(res.getBasis()).isEqualTo("TRADING");
        assertThat(res.isBasisAvailable()).isFalse();
        assertThat(res.isProfileAvailable()).isFalse();
        assertThat(res.getItems()).isEmpty();
        assertThat(res.getBasisFacts()).containsExactly("투자성향 진단 후 거래 경향 추천을 볼 수 있어요.");
        assertThat(res.getSubtitle()).contains("진단");
        assertThat(res.getTradeBuyCount()).isEqualTo(9);
        assertThat(res.getMinBuyCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("거래 기준 질문: 설문 진단이 없으면 AI를 부르지 않고 진단 안내를 준다")
    void askTradingWithoutValidSurvey() {
        candidates(false, null);
        trading(9, new BigDecimal("3"), null, null);

        StockRecommendationAskResponseDTO res =
                service.ask(
                        CUSTOMER_ID,
                        new StockRecommendationAskRequestDTO("뭐 있어?", null, "TRADING", null));

        assertThat(res.getAnswer())
                .isEqualTo(StockRecommendationServiceImpl.TRADING_NEEDS_PROFILE_ANSWER);
        verify(answerer, never()).answer(anyString(), anyString());
    }

    @Test
    @DisplayName("거래 기준 범위 규칙: 보정한 목표를 반올림한 정수의 앞뒤 1등급 ∩ 허용 범위(평균이 범위 안의 소수일 때 포함)")
    void tradingRangeRule() {
        BigDecimal g32 = new BigDecimal("3.2");
        // 허용 2~6, 평균 3.2(범위 안): 보정 없음, 목표는 소수 3.2 그대로, 중심은 반올림한 3 → 2~4
        assertThat(StockRecommendationServiceImpl.tradingTarget(g32, 2))
                .isEqualTo(new StockRecommendationServiceImpl.Target(g32, 2, 4, false));
        // 소수 첫째 자리가 5 이상이면 올린다: 3.5 → 중심 4 → 3~5
        assertThat(StockRecommendationServiceImpl.tradingTarget(new BigDecimal("3.5"), 2))
                .isEqualTo(
                        new StockRecommendationServiceImpl.Target(
                                new BigDecimal("3.5"), 3, 5, false));
        // 평균이 허용 최소 등급과 같으면 보정하지 않는다: 2.0 → 1~3 ∩ 2~6 = 2~3
        assertThat(
                        StockRecommendationServiceImpl.tradingTarget(new BigDecimal("2.0"), 2)
                                .adjusted())
                .isFalse();
        // 평균이 허용 범위보다 위험하면 목표를 허용 최소 등급으로: 1.21 → 목표 2 → 1~3 ∩ 2~6 = 2~3
        StockRecommendationServiceImpl.Target adjusted =
                StockRecommendationServiceImpl.tradingTarget(new BigDecimal("1.21"), 2);
        assertThat(adjusted.adjusted()).isTrue();
        assertThat(adjusted.grade()).isEqualByComparingTo("2");
        assertThat(adjusted.low()).isEqualTo(2);
        assertThat(adjusted.high()).isEqualTo(3);
        // 안전한 쪽 끝: 5.8 → 중심 6 → 5~6
        StockRecommendationServiceImpl.Target safe =
                StockRecommendationServiceImpl.tradingTarget(new BigDecimal("5.8"), 2);
        assertThat(safe.low()).isEqualTo(5);
        assertThat(safe.high()).isEqualTo(6);
    }

    @Test
    @DisplayName("거래 기준 평균 3.2·허용 2~6: 2~4등급만 나오고 소수 3.2에 가까운 순서이며 보정 플래그는 false다")
    void tradingAverageInsideRange() {
        candidates(
                true,
                2,
                row(1, "A", "000001", Type.STOCK, "가", 1, true),
                row(2, "B", "000002", Type.STOCK, "나", 2, true),
                row(3, "C", "000003", Type.STOCK, "다", 3, true),
                row(4, "D", "000004", Type.STOCK, "라", 4, true),
                row(5, "E", "000005", Type.STOCK, "마", 5, true));
        trading(9, new BigDecimal("3.2"), null, null);

        StockRecommendationResponseDTO res = service.recommend(CUSTOMER_ID, "ALL", 6, "TRADING");

        assertThat(res.isTargetAdjusted()).isFalse();
        assertThat(res.getTargetGrade()).isEqualByComparingTo("3.2");
        assertThat(res.getGradeFrom()).isEqualTo(2);
        assertThat(res.getGradeTo()).isEqualTo(4);
        // 3.2와의 거리: 3등급(0.2) < 4등급(0.8) < 2등급(1.2)
        assertThat(tickers(res)).containsExactly("000003", "000004", "000002");
        assertThat(res.getBasisFacts()).noneMatch(f -> f.contains("허용 범위 경계"));
    }

    @Test
    @DisplayName("거래 기준 질문: 체결 매수가 모자라면 AI를 부르지 않고 건수를 안내한다")
    void askTradingNotEnoughBuys() {
        trading(2, null, null, null);

        StockRecommendationAskResponseDTO res =
                service.ask(
                        CUSTOMER_ID,
                        new StockRecommendationAskRequestDTO("뭐 있어?", null, "TRADING", null));

        assertThat(res.getAnswer()).contains("5건").contains("2건");
        verify(answerer, never()).answer(anyString(), anyString());
    }

    @Test
    @DisplayName("limit은 1~12로 맞춘다")
    void limitClamped() {
        candidates(true, 2, row(1, "A", "000001", Type.STOCK, "가", 2, true));

        assertThat(service.recommend(CUSTOMER_ID, "ALL", 0, "SURVEY").getItems()).hasSize(1);
        assertThat(service.recommend(CUSTOMER_ID, "모름", 100, "SURVEY").getFilter())
                .isEqualTo("ALL");
    }

    @Test
    @DisplayName("규칙으로 답할 수 있는 질문은 AI를 부르지 않는다")
    void ruleAnswerSkipsAi() {
        candidates(true, 2, row(1, "A전자", "000001", Type.STOCK, "전기", 2, true));

        StockRecommendationAskResponseDTO res =
                service.ask(
                        CUSTOMER_ID,
                        new StockRecommendationAskRequestDTO("A전자는 왜 골랐어?", null, null, null));

        assertThat(res.isAiGenerated()).isFalse();
        assertThat(res.getTicker()).isEqualTo("000001");
        assertThat(res.getAnswer()).contains("2등급");
        verify(answerer, never()).answer(anyString(), anyString());
    }

    @Test
    @DisplayName("\"이 종목\" 변동성 질문은 펼쳐 본 종목 하나의 값으로만 답한다")
    void focusedVolatilityAnswersOnlyThatItem() {
        candidates(
                true,
                2,
                row(1, "A전자", "000001", Type.STOCK, "전기", 2, true),
                row(2, "B소재", "000002", Type.STOCK, "소재", 2, true));

        StockRecommendationAskResponseDTO res =
                service.ask(
                        CUSTOMER_ID,
                        new StockRecommendationAskRequestDTO(
                                "이 종목 변동성이 어느 정도야?", "000002", null, null));

        assertThat(res.getTicker()).isEqualTo("000002");
        assertThat(res.getAnswer()).contains("B소재").contains("21.5%").doesNotContain("A전자");
        verify(answerer, never()).answer(anyString(), anyString());
    }

    @Test
    @DisplayName("\"이 종목\" 질문이어도 비교를 묻거나 펼친 종목이 없으면 전체 목록으로 답한다")
    void unfocusedOrCompareKeepsListAnswer() {
        candidates(
                true,
                2,
                row(1, "A전자", "000001", Type.STOCK, "전기", 2, true),
                row(2, "B소재", "000002", Type.STOCK, "소재", 2, true));

        assertThat(
                        service.ask(
                                        CUSTOMER_ID,
                                        new StockRecommendationAskRequestDTO(
                                                "이 종목 변동성 알려줘", null, null, null))
                                .getAnswer())
                .contains("A전자")
                .contains("B소재");
        assertThat(
                        service.ask(
                                        CUSTOMER_ID,
                                        new StockRecommendationAskRequestDTO(
                                                "이 종목이랑 변동성 비교해줘", "000001", null, null))
                                .getAnswer())
                .contains("A전자")
                .contains("B소재");
    }

    @Test
    @DisplayName("규칙으로 답하지 못하면 서버 사실을 AI에 넘기고, 실패하면 고정 문구를 준다")
    void aiThenFallback() {
        candidates(true, 2, row(1, "A전자", "000001", Type.STOCK, "전기", 2, true));
        when(answerer.answer(anyString(), anyString())).thenReturn(Optional.of("AI 답"));

        StockRecommendationAskResponseDTO ai =
                service.ask(
                        CUSTOMER_ID,
                        new StockRecommendationAskRequestDTO("이 종목 어때?", "000001", null, null));

        assertThat(ai.isAiGenerated()).isTrue();
        assertThat(ai.getAnswer()).isEqualTo("AI 답");
        ArgumentCaptor<String> facts = ArgumentCaptor.forClass(String.class);
        verify(answerer).answer(anyString(), facts.capture());
        assertThat(facts.getValue()).contains("A전자").contains("2등급");

        when(answerer.answer(anyString(), anyString())).thenReturn(Optional.empty());
        StockRecommendationAskResponseDTO fallback =
                service.ask(
                        CUSTOMER_ID,
                        new StockRecommendationAskRequestDTO("이 종목 어때?", null, null, null));
        assertThat(fallback.isAiGenerated()).isFalse();
        assertThat(fallback.getAnswer()).isEqualTo(StockRecommendationServiceImpl.FALLBACK_ANSWER);
    }

    @Test
    @DisplayName("종목 없이 \"왜 추천했어?\"라고 물으면 AI 없이 추천 기준과 종목별 등급으로 답한다")
    void whyWithoutFocusAnswersFromFacts() {
        candidates(
                true,
                2,
                row(1, "A전자", "000001", Type.STOCK, "전기", 2, true),
                row(2, "B소재", "000002", Type.STOCK, "소재", 3, true));

        StockRecommendationAskResponseDTO res =
                service.ask(
                        CUSTOMER_ID,
                        new StockRecommendationAskRequestDTO("왜 이 종목을 추천했어?", null, null, null));

        assertThat(res.isAiGenerated()).isFalse();
        assertThat(res.getTicker()).isNull();
        assertThat(res.getAnswer())
                .contains("2~6등급")
                .contains("A전자 2등급")
                .contains("B소재 3등급")
                .contains("보유하지 않은")
                .contains("펼치면");
        verify(answerer, never()).answer(anyString(), anyString());
    }

    @Test
    @DisplayName("추천과 상관없는 \"왜\" 질문은 이유 답변으로 가로채지 않고 AI에 넘긴다")
    void whyAboutSomethingElseGoesToAi() {
        candidates(true, 2, row(1, "A전자", "000001", Type.STOCK, "전기", 2, true));
        when(answerer.answer(anyString(), anyString())).thenReturn(Optional.of("AI 답"));

        StockRecommendationAskResponseDTO res =
                service.ask(
                        CUSTOMER_ID,
                        new StockRecommendationAskRequestDTO("삼성전자는 왜 없어?", null, null, null));

        assertThat(res.isAiGenerated()).isTrue();
        assertThat(res.getAnswer()).isEqualTo("AI 답");
    }

    @Test
    @DisplayName("AI 호출이 모두 쓰이는 중이면 \"정보 없음\"이 아니라 잠시 뒤에 다시 물으라고 안내한다")
    void busyAiGetsRetryAnswer() {
        candidates(true, 2, row(1, "A전자", "000001", Type.STOCK, "전기", 2, true));
        when(answerer.answer(anyString(), anyString())).thenThrow(new AiBusyException());

        StockRecommendationAskResponseDTO res =
                service.ask(
                        CUSTOMER_ID,
                        new StockRecommendationAskRequestDTO("이 종목 어때?", null, null, null));

        assertThat(res.isAiGenerated()).isFalse();
        assertThat(res.getTicker()).isNull();
        assertThat(res.getAnswer()).isEqualTo(StockRecommendationServiceImpl.BUSY_ANSWER);
        assertThat(res.getAnswer()).isNotEqualTo(StockRecommendationServiceImpl.FALLBACK_ANSWER);
    }

    @Test
    @DisplayName("진단이 없으면 AI를 부르지 않고 진단 안내를 준다")
    void askWithoutProfile() {
        candidates(false, null);

        StockRecommendationAskResponseDTO res =
                service.ask(
                        CUSTOMER_ID,
                        new StockRecommendationAskRequestDTO("뭐 있어?", null, null, null));

        assertThat(res.getAnswer()).contains("진단");
        verify(answerer, never()).answer(anyString(), anyString());
    }

    @Test
    @DisplayName("질문에 limit을 보내면 화면에 보이는 추천과 같은 종목으로 답하고, 안 보내면 6종목까지 쓴다")
    void askUsesSameSetAsScreenWhenLimitGiven() {
        candidates(
                true,
                2,
                row(1, "A전자", "000001", Type.STOCK, "전기전자", 2, true),
                row(2, "B전자", "000002", Type.STOCK, "전기전자", 2, true),
                row(3, "C화학", "000003", Type.STOCK, "화학", 2, true),
                row(4, "D소재", "000004", Type.STOCK, "소재", 2, true),
                row(5, "E소재", "000005", Type.STOCK, "소재", 2, true));
        List<String> names =
                service.recommend(CUSTOMER_ID, "ALL", 3, "SURVEY").getItems().stream()
                        .map(StockRecommendationResponseDTO.ItemResponse::getName)
                        .toList();
        assertThat(names).hasSize(3);

        String withLimit =
                service.ask(
                                CUSTOMER_ID,
                                new StockRecommendationAskRequestDTO("변동성 알려줘", null, "SURVEY", 3))
                        .getAnswer();
        String withoutLimit =
                service.ask(
                                CUSTOMER_ID,
                                new StockRecommendationAskRequestDTO("변동성 알려줘", null, null, null))
                        .getAnswer();

        List<String> all = List.of("A전자", "B전자", "C화학", "D소재", "E소재");
        for (String n : all) {
            assertThat(withLimit.contains(n)).as("limit=3 답변의 " + n).isEqualTo(names.contains(n));
            assertThat(withoutLimit).as("limit 없는 답변").contains(n);
        }
    }

    @Test
    @DisplayName("변동성과 위험이 같이 나오면 변동성 답을 하고, 위험등급만 물으면 등급 답을 한다")
    void volatilityQuestionWinsOverRiskWord() {
        candidates(true, 2, row(1, "A전자", "000001", Type.STOCK, "전기", 2, true));

        String vol =
                service.ask(
                                CUSTOMER_ID,
                                new StockRecommendationAskRequestDTO(
                                        "변동성 위험 알려줘", null, null, null))
                        .getAnswer();
        String grade =
                service.ask(
                                CUSTOMER_ID,
                                new StockRecommendationAskRequestDTO("위험등급 알려줘", null, null, null))
                        .getAnswer();

        assertThat(vol).contains("연 변동성은").contains("21.5%");
        assertThat(grade).contains("위험등급은 1등급").doesNotContain("연 변동성은");
    }
}
