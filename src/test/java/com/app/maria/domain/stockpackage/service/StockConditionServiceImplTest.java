package com.app.maria.domain.stockpackage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.domestic.type.Type;
import com.app.maria.domain.stockpackage.ai.ConditionDraft;
import com.app.maria.domain.stockpackage.ai.ConditionDraft.Intent;
import com.app.maria.domain.stockpackage.ai.StockConditionParser;
import com.app.maria.domain.stockpackage.dto.request.StockConditionParseRequestDTO;
import com.app.maria.domain.stockpackage.dto.response.StockConditionParseResponseDTO;
import com.app.maria.domain.stockpackage.mapper.StockCandidateMapper;
import com.app.maria.domain.stockpackage.type.CandidateSort;
import com.app.maria.domain.stockpackage.type.SortDirection;
import com.app.maria.domain.stockpackage.type.StockPackageSentenceTest;
import com.app.maria.global.clock.service.BusinessClockService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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
class StockConditionServiceImplTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 10, 0);

    @Mock private StockConditionParser parser;
    @Mock private StockCandidateMapper candidateMapper;
    @Mock private BusinessClockService clock;
    @InjectMocks private StockConditionServiceImpl service;

    @BeforeEach
    void setUp() {
        when(clock.now()).thenReturn(NOW);
        when(candidateMapper.selectThemeNames()).thenReturn(List.of("2차전지", "반도체", "배당"));
    }

    private StockConditionParseResponseDTO parse(String text) {
        return service.parse(new StockConditionParseRequestDTO(text));
    }

    private void aiReturns(ConditionDraft draft) {
        when(parser.parse(anyString(), anyList())).thenReturn(Optional.of(draft));
    }

    private static ConditionDraft filter(
            List<Type> types, List<String> themes, Integer min, Integer max) {
        return new ConditionDraft(Intent.FILTER, types, themes, min, max, null, null, null, null);
    }

    @Test
    @DisplayName("조건을 말하면 검증된 값으로 조건과 안내 문장을 만든다")
    void parsed() {
        aiReturns(
                new ConditionDraft(
                        Intent.FILTER,
                        List.of(Type.FUND),
                        List.of("배당"),
                        2,
                        4,
                        3,
                        5,
                        CandidateSort.RISK_GRADE,
                        SortDirection.DESC));

        StockConditionParseResponseDTO r = parse("배당 ETF를 2등급부터 4등급까지");

        assertThat(r.getStatus()).isEqualTo("PARSED");
        assertThat(r.isPartial()).isFalse();
        assertThat(r.getFilter().getProductTypes()).containsExactly(Type.FUND);
        assertThat(r.getFilter().getThemes()).containsExactly("배당");
        assertThat(r.getFilter().getMinGrade()).isEqualTo(2);
        assertThat(r.getFilter().getMaxGrade()).isEqualTo(4);
        assertThat(r.getFilter().getItemCountMin()).isEqualTo(3);
        assertThat(r.getFilter().getSort()).isEqualTo(CandidateSort.RISK_GRADE);
        assertThat(r.getMessages())
                .containsExactly(
                        "말씀하신 내용에서 찾은 조건은 다음과 같아요.",
                        "펀드 상품군으로 찾아요.",
                        "배당 테마로 찾아요.",
                        "위험등급은 2등급부터 4등급까지 범위로 찾아요.",
                        "종목 수는 3종목부터 5종목까지 범위로 정했어요.",
                        "정렬 기준은 위험등급 6등급부터 방식이에요.",
                        "이 조건으로 후보를 걸러 볼 수 있어요.");
        assertThat(r.getDisclaimer()).isEqualTo("투자 권유가 아닌 정보 제공이에요");
    }

    @Test
    @DisplayName("투자 판단을 묻는 말은 AI를 부르지 않고 고정 안내를 준다")
    void adviceRefusedBeforeAi() {
        for (String text :
                List.of(
                        "뭐 사야 돼?",
                        "삼성전자 사도 돼?",
                        "이거 살까요",
                        "지금 팔아야 하나",
                        "배당주 추천해줘",
                        "어떤 종목 담을까",
                        "내일 오를까?",
                        "수익 날 종목 알려줘")) {
            StockConditionParseResponseDTO r = parse(text);

            assertThat(r.getStatus()).as(text).isEqualTo("ADVICE_REFUSED");
            assertThat(r.getFilter()).as(text).isNull();
            assertThat(r.getMessages())
                    .containsExactly(
                            "특정 상품에 대한 투자 권유는 하지 않아요.",
                            "대신 상품군·테마·위험등급 조건으로 후보를 걸러 볼 수 있고, 직접 담은 패키지는 점검할 수 있어요.");
        }
        verify(parser, never()).parse(anyString(), anyList());
    }

    @Test
    @DisplayName("등급이나 종목 수가 하나의 값이면 범위가 아니라 그 값으로 안내한다")
    void exactValues() {
        aiReturns(
                new ConditionDraft(
                        Intent.FILTER, List.of(Type.STOCK), List.of("배당"), 3, 3, 5, 5, null, null));

        StockConditionParseResponseDTO r = parse("배당 주식 3등급 5종목");

        assertThat(r.getMessages())
                .contains("위험등급은 3등급으로 찾아요.", "종목 수는 5종목으로 정했어요.")
                .noneMatch(m -> m.contains("부터"));
    }

    @Test
    @DisplayName("AI가 투자 판단 요청으로 분류해도 조건으로 바꾸지 않는다")
    void aiClassifiesAdvice() {
        aiReturns(
                new ConditionDraft(
                        Intent.ADVICE_REQUEST,
                        List.of(Type.STOCK),
                        List.of("배당"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));

        StockConditionParseResponseDTO r = parse("안정적인 배당주 하나만 골라줘");

        assertThat(r.getStatus()).isEqualTo("ADVICE_REFUSED");
        assertThat(r.getFilter()).isNull();
    }

    @Test
    @DisplayName("AI가 만든 문자열은 고객에게 보이는 문장에 들어가지 않는다")
    void aiTextNeverShown() {
        aiReturns(filter(null, List.of("배당", "삼성전자를 지금 사세요", "005930 추천"), null, null));

        StockConditionParseResponseDTO r = parse("배당 위주로");

        assertThat(r.getStatus()).isEqualTo("PARSED");
        assertThat(r.isPartial()).isTrue();
        assertThat(r.getFilter().getThemes()).containsExactly("배당");
        String all = String.join(" ", r.getMessages());
        assertThat(all).doesNotContain("삼성전자").doesNotContain("005930").doesNotContain("사세요");
        assertThat(all).contains("이해하지 못한 조건은 적용하지 않았어요.");
    }

    @Test
    @DisplayName("유효한 조건이 하나도 없으면 이해하지 못했다고 안내한다")
    void nothingUsable() {
        aiReturns(filter(null, List.of("삼성전자"), null, null));
        assertThat(parse("삼성전자").getStatus()).isEqualTo("NOT_UNDERSTOOD");

        aiReturns(new ConditionDraft(Intent.OTHER, null, null, null, null, null, null, null, null));
        StockConditionParseResponseDTO other = parse("오늘 날씨 어때");
        assertThat(other.getStatus()).isEqualTo("NOT_UNDERSTOOD");
        assertThat(other.getMessages())
                .containsExactly("조건을 이해하지 못했어요. 상품군·테마·위험등급으로 직접 골라 볼 수 있어요.");
    }

    @Test
    @DisplayName("AI를 쓸 수 없으면 오류 없이 고정 안내를 준다")
    void aiUnavailable() {
        when(parser.parse(anyString(), anyList())).thenReturn(Optional.empty());

        StockConditionParseResponseDTO r = parse("배당 ETF");

        assertThat(r.getStatus()).isEqualTo("UNAVAILABLE");
        assertThat(r.getFilter()).isNull();
        assertThat(r.getMessages())
                .containsExactly("지금은 말로 조건을 찾을 수 없어요. 상품군·테마·위험등급으로 직접 골라 볼 수 있어요.");
    }

    @Test
    @DisplayName("안내 문장에는 권유·평가 표현이 없다(권유를 하지 않는다는 안내는 예외)")
    void messagesHaveNoAdvice() {
        aiReturns(
                new ConditionDraft(
                        Intent.FILTER,
                        List.of(Type.STOCK, Type.FUND),
                        List.of("배당", "반도체", "없는테마"),
                        1,
                        6,
                        1,
                        10,
                        CandidateSort.VOLATILITY,
                        SortDirection.ASC));
        String parsed = String.join(" ", parse("조건").getMessages());
        for (String banned : StockPackageSentenceTest.BANNED) {
            assertThat(parsed).as("금지 표현 '%s'", banned).doesNotContain(banned);
        }
    }
}
