package com.app.maria.domain.stockpackage.type;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 문장은행의 모든 문장을 검사한다. 고객에게 보이는 설명은 여기 있는 문장에 값만 끼워서 만들어지므로, 이 테스트가 통과하면 권유·평가·전망 표현은 나올 수 없다. 문장을
 * 추가하거나 고칠 때마다 자동으로 걸린다.
 */
public class StockPackageSentenceTest {
    /** 권유·평가·전망·지시를 나타내는 말. 새 표현이 필요하면 문장이 아니라 이 목록을 먼저 늘린다. */
    public static final List<String> BANNED =
            List.of(
                    "추천", "권유", "권해", "권장", "사세요", "사라", "사는", "매수하", "매수해", "매도하", "매도해", "담아",
                    "담으", "담는", "팔아", "팔라", "파는", "줄이", "줄여", "늘리", "늘려", "유망", "전망", "예상", "기대",
                    "좋", "나쁘", "낫", "유리", "불리", "안전", "괜찮", "적절", "바람직", "리밸런싱", "세요", "십시오", "죠",
                    "하자", "해라", "보세요", "게 좋", "것이 좋", "수익", "손실", "오를", "내릴", "상승", "하락", "급등",
                    "급락");

    private static final Pattern SLOT = Pattern.compile("\\{([A-Za-z]+)}");

    @Test
    @DisplayName("모든 문장에 권유·평가·전망·지시 표현이 없다")
    void noBannedWords() {
        for (StockPackageSentence s : StockPackageSentence.values()) {
            for (String banned : BANNED) {
                // 수익은 "예측하는 값이 아니에요" 문장의 설명에 한해 쓰므로 해당 문장만 예외로 둔다.
                if (s == StockPackageSentence.VOLATILITY_REFERENCE && banned.equals("수익")) {
                    continue;
                }
                // "투자 권유는 하지 않아요"는 권유를 하지 않는다는 안내 문장이라 이 단어에 한해 예외로 둔다.
                if (s == StockPackageSentence.CONDITION_ADVICE_REFUSED && banned.equals("권유")) {
                    continue;
                }
                assertThat(s.template())
                        .as("%s 에 금지 표현 '%s'", s.name(), banned)
                        .doesNotContain(banned);
            }
        }
    }

    @Test
    @DisplayName("모든 문장은 서술형('요.')으로 끝난다")
    void endsWithDeclarative() {
        for (StockPackageSentence s : StockPackageSentence.values()) {
            assertThat(s.template()).as(s.name()).endsWith("요.");
            assertThat(s.template()).as(s.name()).doesNotContain("?").doesNotContain("!");
        }
    }

    @Test
    @DisplayName("문장에 쓰인 슬롯은 허용 목록 안에 있다")
    void slotsAllowed() {
        Set<String> used = new HashSet<>();
        for (StockPackageSentence s : StockPackageSentence.values()) {
            Matcher m = SLOT.matcher(s.template());
            while (m.find()) {
                used.add(m.group(1));
                assertThat(StockPackageSentence.ALLOWED_SLOTS)
                        .as("%s 의 슬롯 %s", s.name(), m.group(1))
                        .contains(m.group(1));
            }
            // 슬롯 말고 남은 중괄호가 없어야 한다.
            assertThat(m.reset().replaceAll(""))
                    .as(s.name())
                    .doesNotContain("{")
                    .doesNotContain("}");
        }
        assertThat(used).isNotEmpty();
    }

    @Test
    @DisplayName("슬롯을 채우면 문장이 되고, 값이 빠지면 오류다")
    void render() {
        assertThat(StockPackageSentence.AVG_GRADE_FACT.render(Map.of("avg", "3.80")))
                .isEqualTo("이 패키지의 위험등급 가중평균은 3.80등급이에요.");
        assertThatThrownBy(() -> StockPackageSentence.AVG_GRADE_FACT.render(Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("금지어 목록이 실제로 걸러내는지 확인한다")
    void bannedListWorks() {
        List<String> advice =
                List.of(
                        "이 종목을 담아 보세요.",
                        "비중을 줄이는 게 좋아요.",
                        "유망한 종목이에요.",
                        "더 사는 것도 방법이죠.",
                        "수익이 기대돼요.");
        for (String text : advice) {
            assertThat(BANNED.stream().anyMatch(text::contains)).as(text).isTrue();
        }
    }
}
