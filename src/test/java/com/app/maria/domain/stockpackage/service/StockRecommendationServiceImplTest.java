package com.app.maria.domain.stockpackage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.domestic.type.Type;
import com.app.maria.domain.stockpackage.ai.StockRecommendationAnswerer;
import com.app.maria.domain.stockpackage.dto.request.StockCandidateRequestDTO;
import com.app.maria.domain.stockpackage.dto.request.StockRecommendationAskRequestDTO;
import com.app.maria.domain.stockpackage.dto.response.StockCandidateResponseDTO;
import com.app.maria.domain.stockpackage.dto.response.StockRecommendationAskResponseDTO;
import com.app.maria.domain.stockpackage.dto.response.StockRecommendationResponseDTO;
import com.app.maria.domain.stockpackage.vo.StockCandidateListVO;
import com.app.maria.domain.stockpackage.vo.StockCandidateListVO.Row;
import com.app.maria.global.clock.service.BusinessClockService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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
    @InjectMocks private StockRecommendationServiceImpl service;

    @BeforeEach
    void setUp() {
        when(clock.now()).thenReturn(NOW);
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
                service.ask(CUSTOMER_ID, new StockRecommendationAskRequestDTO("A전자는 왜 골랐어?", null));

        assertThat(res.isAiGenerated()).isFalse();
        assertThat(res.getTicker()).isEqualTo("000001");
        assertThat(res.getAnswer()).contains("2등급");
        verify(answerer, never()).answer(anyString(), anyString());
    }

    @Test
    @DisplayName("규칙으로 답하지 못하면 서버 사실을 AI에 넘기고, 실패하면 고정 문구를 준다")
    void aiThenFallback() {
        candidates(true, 2, row(1, "A전자", "000001", Type.STOCK, "전기", 2, true));
        when(answerer.answer(anyString(), anyString())).thenReturn(Optional.of("AI 답"));

        StockRecommendationAskResponseDTO ai =
                service.ask(
                        CUSTOMER_ID, new StockRecommendationAskRequestDTO("이 종목 어때?", "000001"));

        assertThat(ai.isAiGenerated()).isTrue();
        assertThat(ai.getAnswer()).isEqualTo("AI 답");
        ArgumentCaptor<String> facts = ArgumentCaptor.forClass(String.class);
        verify(answerer).answer(anyString(), facts.capture());
        assertThat(facts.getValue()).contains("A전자").contains("2등급");

        when(answerer.answer(anyString(), anyString())).thenReturn(Optional.empty());
        StockRecommendationAskResponseDTO fallback =
                service.ask(CUSTOMER_ID, new StockRecommendationAskRequestDTO("이 종목 어때?", null));
        assertThat(fallback.isAiGenerated()).isFalse();
        assertThat(fallback.getAnswer()).isEqualTo(StockRecommendationServiceImpl.FALLBACK_ANSWER);
    }

    @Test
    @DisplayName("진단이 없으면 AI를 부르지 않고 진단 안내를 준다")
    void askWithoutProfile() {
        candidates(false, null);

        StockRecommendationAskResponseDTO res =
                service.ask(CUSTOMER_ID, new StockRecommendationAskRequestDTO("뭐 있어?", null));

        assertThat(res.getAnswer()).contains("진단");
        verify(answerer, never()).answer(anyString(), anyString());
    }
}
