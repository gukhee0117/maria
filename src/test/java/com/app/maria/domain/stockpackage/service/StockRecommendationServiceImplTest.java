package com.app.maria.domain.stockpackage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.app.maria.domain.stockpackage.dto.response.StockRecommendationAskResponseDTO;
import com.app.maria.domain.stockpackage.dto.response.StockRecommendationResponseDTO;
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
    @Mock private AccountMapper accountMapper;
    @Mock private StockOrderMapper stockOrderMapper;
    @InjectMocks private StockRecommendationServiceImpl service;

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

        StockRecommendationResponseDTO res = service.recommend(CUSTOMER_ID, "ALL", 3);

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

        StockRecommendationResponseDTO res = service.recommend(CUSTOMER_ID, "ALL", 2);

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

        StockRecommendationResponseDTO res = service.recommend(CUSTOMER_ID, "ALL", 1);

        assertThat(res.getItems().get(0).getTicker()).isEqualTo("000002");
    }

    @Test
    @DisplayName("허용 범위의 가장 안전한 끝(6등급)까지 후보가 되고, 허용 경계(2등급)에 가까운 순서로 나온다")
    void rangeCoversWholeAllowedBandUpToGradeSix() {
        candidates(
                true,
                2,
                row(1, "A", "000001", Type.STOCK, "가", 6, true),
                row(2, "B", "000002", Type.STOCK, "나", 3, true),
                row(3, "C", "000003", Type.STOCK, "다", 2, true),
                row(4, "D", "000004", Type.STOCK, "라", 1, true));

        StockRecommendationResponseDTO res = service.recommend(CUSTOMER_ID, "ALL", 6);

        // 허용 2~6등급: 1등급은 범위 밖이라 빠지고, 6등급은 들어온다
        assertThat(tickers(res)).containsExactly("000003", "000002", "000001");
    }

    @Test
    @DisplayName("추천 이유에는 설문 허용 범위·테마·변동성·RIA 구매 가능 문장이 들어간다")
    void reasonsCarryProfileThemeAndVolatility() {
        candidates(true, 2, rowT(1, "000001", "가", 3, List.of("반도체", "2차전지")));

        String reasons =
                String.join(
                        " ",
                        service.recommend(CUSTOMER_ID, "ALL", 3).getItems().get(0).getReasons());

        assertThat(reasons)
                .contains("허용 범위(2~6등급) 안의 3등급")
                .contains("RIA 계좌에서 살 수 있는 국내 상장 주식")
                .contains("연 변동성은 21.5%")
                .contains("반도체 · 2차전지 테마에 속해요.");
    }

    @Test
    @DisplayName("테마가 없는 상품은 추천 이유에 업종 문장이 들어간다")
    void reasonsFallBackToSectorWhenNoTheme() {
        candidates(true, 2, rowT(1, "000001", "전기전자", 3, List.of()));

        String reasons =
                String.join(
                        " ",
                        service.recommend(CUSTOMER_ID, "ALL", 3).getItems().get(0).getReasons());

        assertThat(reasons).contains("전기전자 업종이에요.").doesNotContain("테마에 속해요");
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
    @DisplayName("(c) 이미 보유한 종목은 추천하지 않는다")
    void heldProductsAreExcluded() {
        candidates(
                true,
                2,
                row(1, "A", "000001", Type.STOCK, "가", 2, true),
                row(2, "B", "000002", Type.STOCK, "나", 3, true),
                row(3, "C", "000003", Type.STOCK, "다", 3, true));
        holding(1L, 2L);

        assertThat(tickers(service.recommend(CUSTOMER_ID, "ALL", 6))).containsExactly("000003");
    }

    @Test
    @DisplayName("(d) 전량 매도한 종목(보유 0, 매수 이력 있음)은 후보에 다시 들어갈 수 있다")
    void soldOutProductCanBeRecommendedAgain() {
        // 종목 1은 예전에 샀다가 전부 팔았다: 매수 이력은 있지만 잔량 행이 없어 보유 목록에 없다
        candidates(
                true,
                2,
                row(1, "A", "000001", Type.STOCK, "가", 3, true),
                row(2, "B", "000002", Type.STOCK, "나", 3, true));
        holding(); // 잔량 있는 보유 없음

        assertThat(tickers(service.recommend(CUSTOMER_ID, "ALL", 6))).contains("000001");
    }

    @Test
    @DisplayName("계좌가 없는 고객은 보유 종목 없음으로 보고 오류 없이 추천한다")
    void noAccountMeansNoHoldings() {
        when(accountMapper.selectByCustomerId(any())).thenReturn(Optional.empty());
        candidates(true, 2, row(1, "A", "000001", Type.STOCK, "가", 2, true));

        assertThat(tickers(service.recommend(CUSTOMER_ID, "ALL", 3))).containsExactly("000001");
        verify(stockOrderMapper, never()).selectHeldBalancesByAccount(any());
    }

    @Test
    @DisplayName("limit은 1~12로 맞춘다")
    void limitClamped() {
        candidates(true, 2, row(1, "A", "000001", Type.STOCK, "가", 2, true));

        assertThat(service.recommend(CUSTOMER_ID, "ALL", 0).getItems()).hasSize(1);
        assertThat(service.recommend(CUSTOMER_ID, "모름", 100).getFilter()).isEqualTo("ALL");
    }

    @Test
    @DisplayName("규칙으로 답할 수 있는 질문은 AI를 부르지 않는다")
    void ruleAnswerSkipsAi() {
        candidates(true, 2, row(1, "A전자", "000001", Type.STOCK, "전기", 2, true));

        StockRecommendationAskResponseDTO res =
                service.ask(
                        CUSTOMER_ID,
                        new StockRecommendationAskRequestDTO("A전자는 왜 골랐어?", null, null));

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
                        new StockRecommendationAskRequestDTO("이 종목 변동성이 어느 정도야?", "000002", null));

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
                                                "이 종목 변동성 알려줘", null, null))
                                .getAnswer())
                .contains("A전자")
                .contains("B소재");
        assertThat(
                        service.ask(
                                        CUSTOMER_ID,
                                        new StockRecommendationAskRequestDTO(
                                                "이 종목이랑 변동성 비교해줘", "000001", null))
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
                        new StockRecommendationAskRequestDTO("이 종목 어때?", "000001", null));

        assertThat(ai.isAiGenerated()).isTrue();
        assertThat(ai.getAnswer()).isEqualTo("AI 답");
        ArgumentCaptor<String> facts = ArgumentCaptor.forClass(String.class);
        verify(answerer).answer(anyString(), facts.capture());
        assertThat(facts.getValue()).contains("A전자").contains("2등급");

        when(answerer.answer(anyString(), anyString())).thenReturn(Optional.empty());
        StockRecommendationAskResponseDTO fallback =
                service.ask(
                        CUSTOMER_ID, new StockRecommendationAskRequestDTO("이 종목 어때?", null, null));
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
                        new StockRecommendationAskRequestDTO("왜 이 종목을 추천했어?", null, null));

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
                        new StockRecommendationAskRequestDTO("삼성전자는 왜 없어?", null, null));

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
                        CUSTOMER_ID, new StockRecommendationAskRequestDTO("이 종목 어때?", null, null));

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
                service.ask(CUSTOMER_ID, new StockRecommendationAskRequestDTO("뭐 있어?", null, null));

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
                service.recommend(CUSTOMER_ID, "ALL", 3).getItems().stream()
                        .map(StockRecommendationResponseDTO.ItemResponse::getName)
                        .toList();
        assertThat(names).hasSize(3);

        String withLimit =
                service.ask(CUSTOMER_ID, new StockRecommendationAskRequestDTO("변동성 알려줘", null, 3))
                        .getAnswer();
        String withoutLimit =
                service.ask(
                                CUSTOMER_ID,
                                new StockRecommendationAskRequestDTO("변동성 알려줘", null, null))
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
                                new StockRecommendationAskRequestDTO("변동성 위험 알려줘", null, null))
                        .getAnswer();
        String grade =
                service.ask(
                                CUSTOMER_ID,
                                new StockRecommendationAskRequestDTO("위험등급 알려줘", null, null))
                        .getAnswer();

        assertThat(vol).contains("연 변동성은").contains("21.5%");
        assertThat(grade).contains("위험등급은 1등급").doesNotContain("연 변동성은");
    }

    /** 테마 행. out이 true면 후보 서비스가 성향 범위 밖으로 표시한 상품이다. */
    private static Row themed(long id, String name, String sector, int grade, boolean out) {
        return new Row(
                id,
                String.format("%06d", id),
                name,
                "KOSPI",
                Type.STOCK,
                sector,
                List.of("반도체"),
                grade,
                new BigDecimal("30.0"),
                out,
                true,
                null);
    }

    @Test
    @DisplayName("테마를 말하면 그 테마로 좁혀 범위 안 상품만 추천하고, 범위 밖 상품은 개수만 알린다")
    void themeAskRecommendsInProfileOnly() {
        when(candidateService.themeNames()).thenReturn(List.of("반도체", "배당"));
        candidates(
                true,
                2,
                themed(1, "A반도체", "전기전자", 3, false),
                themed(2, "B반도체", "전기전자", 1, true),
                themed(3, "C반도체", "장비", 1, true));

        StockRecommendationAskResponseDTO res =
                service.ask(
                        CUSTOMER_ID, new StockRecommendationAskRequestDTO("반도체 종목 추천해줘", null, 3));

        ArgumentCaptor<StockCandidateRequestDTO> req =
                ArgumentCaptor.forClass(StockCandidateRequestDTO.class);
        verify(candidateService).search(any(), req.capture());
        assertThat(req.getValue().themes()).containsExactly("반도체");
        // 범위 밖 개수를 세려면 후보 서비스가 범위 밖 상품도 돌려줘야 한다(false면 개수가 늘 0이 된다)
        assertThat(req.getValue().includeOutOfProfile()).isTrue();

        String answer = res.getAnswer();
        assertThat(answer).startsWith("반도체 테마 안에서 내 투자성향 범위 안");
        assertThat(answer).contains("A반도체").doesNotContain("B반도체").doesNotContain("C반도체");
        assertThat(answer).contains("범위 밖 상품도 2개 있어요").contains("후보 찾기에서 직접 확인");
        assertThat(res.isAiGenerated()).isFalse();
        verify(answerer, never()).answer(anyString(), anyString());
    }

    @Test
    @DisplayName("테마 안에 범위 밖 상품이 없으면 범위 밖 안내를 붙이지 않는다")
    void themeAskWithoutOutOfProfileHasNoNote() {
        when(candidateService.themeNames()).thenReturn(List.of("반도체"));
        candidates(true, 2, themed(1, "A반도체", "전기전자", 3, false), themed(2, "B반도체", "장비", 2, false));

        String answer =
                service.ask(CUSTOMER_ID, new StockRecommendationAskRequestDTO("반도체 추천", null, 2))
                        .getAnswer();

        assertThat(answer).contains("A반도체").contains("B반도체").doesNotContain("범위 밖");
    }

    @Test
    @DisplayName("테마 안에 범위 안 상품이 없으면 추천하지 않고, 범위 밖 개수와 후보 화면을 안내한다")
    void themeAskOnlyOutOfProfile() {
        when(candidateService.themeNames()).thenReturn(List.of("반도체"));
        candidates(true, 4, themed(1, "A반도체", "가", 1, true), themed(2, "B반도체", "나", 2, true));

        String answer =
                service.ask(CUSTOMER_ID, new StockRecommendationAskRequestDTO("반도체 추천해줘", null, 3))
                        .getAnswer();

        assertThat(answer)
                .startsWith("반도체 테마에는 내 투자성향 범위 안이고")
                .contains("범위 밖 상품도 2개 있어요")
                .doesNotContain("A반도체")
                .doesNotContain("B반도체");
    }

    @Test
    @DisplayName("테마에 고를 상품이 하나도 없으면 범위 밖 안내 없이 없다고만 알린다")
    void themeAskEmpty() {
        when(candidateService.themeNames()).thenReturn(List.of("반도체"));
        candidates(true, 2);

        String answer =
                service.ask(
                                CUSTOMER_ID,
                                new StockRecommendationAskRequestDTO("반도체 추천해줘", null, null))
                        .getAnswer();

        assertThat(answer).startsWith("반도체 테마에는").doesNotContain("범위 밖");
    }

    @Test
    @DisplayName("(리뷰) 테마 질문이 위험등급·변동성·비교 규칙 답에 걸려도 범위 밖 종목은 답에 나오지 않는다")
    void themeRuleAnswersNeverListOutOfProfile() {
        when(candidateService.themeNames()).thenReturn(List.of("반도체"));
        candidates(true, 2, themed(1, "A반도체", "전기전자", 3, false), themed(2, "B반도체", "장비", 1, true));

        for (String q : List.of("반도체 위험등급 알려줘", "반도체 변동성 알려줘", "반도체 종목 비교해줘")) {
            String answer =
                    service.ask(CUSTOMER_ID, new StockRecommendationAskRequestDTO(q, null, 2))
                            .getAnswer();

            assertThat(answer).as(q).contains("A반도체").doesNotContain("B반도체");
        }
        verify(answerer, never()).answer(anyString(), anyString());
    }

    @Test
    @DisplayName("이미 보유했거나 등급이 없는 범위 밖 상품은 범위 밖 개수에 세지 않는다")
    void outOfProfileCountSkipsHeldAndUngraded() {
        when(candidateService.themeNames()).thenReturn(List.of("반도체"));
        holding(2L);
        Row ungraded =
                new Row(
                        3L,
                        "000003",
                        "C반도체",
                        "KOSPI",
                        Type.STOCK,
                        "다",
                        List.of("반도체"),
                        null,
                        null,
                        null,
                        false,
                        "등급 없음");
        candidates(
                true,
                2,
                themed(1, "A반도체", "가", 3, false),
                themed(2, "B반도체", "나", 1, true),
                ungraded,
                themed(4, "D반도체", "라", 1, true));

        String answer =
                service.ask(CUSTOMER_ID, new StockRecommendationAskRequestDTO("반도체 추천", null, 3))
                        .getAnswer();

        assertThat(answer).contains("범위 밖 상품도 1개 있어요");
    }

    @Test
    @DisplayName("테마를 말하지 않은 추천은 지금처럼 성향 범위 안 상품만 받는다")
    void defaultRecommendStaysInProfile() {
        candidates(true, 2, row(1, "A", "000001", Type.STOCK, "가", 2, true));

        service.recommend(CUSTOMER_ID, "ALL", 3);

        ArgumentCaptor<StockCandidateRequestDTO> req =
                ArgumentCaptor.forClass(StockCandidateRequestDTO.class);
        verify(candidateService).search(any(), req.capture());
        assertThat(req.getValue().includeOutOfProfile()).isFalse();
        assertThat(req.getValue().themes()).isNull();
    }

    @Test
    @DisplayName("(리뷰) 테마 이름은 띄어쓰기와 대소문자가 달라도 찾는다")
    void themeMatchIgnoresSpacesAndCase() {
        when(candidateService.themeNames()).thenReturn(List.of("2차전지", "AI반도체"));
        candidates(true, 2, themed(1, "A", "가", 3, false));

        service.ask(CUSTOMER_ID, new StockRecommendationAskRequestDTO("2차 전지 추천", null, 1));
        service.ask(CUSTOMER_ID, new StockRecommendationAskRequestDTO("ai 반도체 추천", null, 1));

        ArgumentCaptor<StockCandidateRequestDTO> req =
                ArgumentCaptor.forClass(StockCandidateRequestDTO.class);
        verify(candidateService, org.mockito.Mockito.times(2)).search(any(), req.capture());
        assertThat(req.getAllValues())
                .extracting(StockCandidateRequestDTO::themes)
                .containsExactly(List.of("2차전지"), List.of("AI반도체"));
    }

    @Test
    @DisplayName("(재리뷰) 테마 질문이 규칙 답에 걸려도 범위 밖 개수와 후보 화면 안내를 붙인다")
    void themeRuleAnswersCarryOutOfProfileNote() {
        when(candidateService.themeNames()).thenReturn(List.of("반도체"));
        candidates(true, 2, themed(1, "A반도체", "전기전자", 3, false), themed(2, "B반도체", "장비", 1, true));

        for (String q : List.of("반도체 위험등급 알려줘", "반도체 변동성 알려줘", "반도체 종목 비교해줘")) {
            String answer =
                    service.ask(CUSTOMER_ID, new StockRecommendationAskRequestDTO(q, null, 2))
                            .getAnswer();

            assertThat(answer).as(q).contains("범위 밖 상품도 1개 있어요").doesNotContain("B반도체");
        }
        // 테마가 없는 규칙 답에는 붙지 않는다
        candidates(true, 2, row(1, "A", "000001", Type.STOCK, "가", 3, true));
        assertThat(
                        service.ask(
                                        CUSTOMER_ID,
                                        new StockRecommendationAskRequestDTO("위험등급 알려줘", null, 1))
                                .getAnswer())
                .doesNotContain("범위 밖 상품도");
    }

    @Test
    @DisplayName("(재리뷰) 두 글자 테마는 공백을 지우지 않고 비교해 다른 단어에 걸리지 않는다")
    void shortThemeNeedsExactWord() {
        when(candidateService.themeNames()).thenReturn(List.of("건설", "2차전지"));
        candidates(true, 2, row(1, "A", "000001", Type.STOCK, "가", 3, true));

        service.ask(CUSTOMER_ID, new StockRecommendationAskRequestDTO("이 건 설명해줘", null, 1));
        service.ask(CUSTOMER_ID, new StockRecommendationAskRequestDTO("건설 추천", null, 1));

        ArgumentCaptor<StockCandidateRequestDTO> req =
                ArgumentCaptor.forClass(StockCandidateRequestDTO.class);
        verify(candidateService, org.mockito.Mockito.times(2)).search(any(), req.capture());
        assertThat(req.getAllValues().get(0).themes()).isNull();
        assertThat(req.getAllValues().get(1).themes()).containsExactly("건설");
    }

    @Test
    @DisplayName("(재리뷰) 짧은 영문 테마는 다른 영어 단어 안에서 맞지 않고 단어로 쓰일 때만 맞는다")
    void shortEnglishThemeNeedsWordBoundary() {
        when(candidateService.themeNames()).thenReturn(List.of("AI"));
        candidates(true, 2, row(1, "A", "000001", Type.STOCK, "가", 3, true));

        service.ask(CUSTOMER_ID, new StockRecommendationAskRequestDTO("mail chain 추천해줘", null, 1));
        service.ask(CUSTOMER_ID, new StockRecommendationAskRequestDTO("ai 추천해줘", null, 1));

        ArgumentCaptor<StockCandidateRequestDTO> req =
                ArgumentCaptor.forClass(StockCandidateRequestDTO.class);
        verify(candidateService, org.mockito.Mockito.times(2)).search(any(), req.capture());
        assertThat(req.getAllValues().get(0).themes()).isNull();
        assertThat(req.getAllValues().get(1).themes()).containsExactly("AI");
    }
}
