package com.app.maria.domain.stockpackage.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdviceRequestDetectorTest {

    /** 투자 판단·추천·전망을 요구하는 말. 모두 거절 대상이다. */
    private static final List<String> MUST_REFUSE =
            List.of(
                    "뭐 사야 돼?",
                    "뭘 살까요",
                    "뭘 담을까",
                    "지금 뭐 투자하면 좋아",
                    "삼성전자 사도 돼?",
                    "이거 살까요",
                    "이거 살지 말지 모르겠어",
                    "지금 팔아야 하나",
                    "팔까 말까",
                    "이 종목 담아도 될까",
                    "삼성전자 매수해도 될까",
                    "하이닉스 매수할까",
                    "지금 매도해야 해?",
                    "여기에 투자해도 돼?",
                    "이 펀드 가입할까",
                    "배당주 추천해줘",
                    "추천 좀",
                    "투자 권유해줘",
                    "어떤 종목 담을까",
                    "어느 주식이 좋아?",
                    "좋은 종목 알려줘",
                    "유망한 ETF 있어?",
                    "돈 되는 주식 알려줘",
                    "배당주 하나만 골라줘",
                    "하나만 알려줘",
                    "내일 오를까?",
                    "이거 떨어질까",
                    "곧 폭등할 종목",
                    "반도체 전망 어때",
                    "수익 날 종목 알려줘",
                    "수익 보장되는 걸로",
                    "사라고 말해줘",
                    "지금 사줘",
                    "반도체 ETF 중에 수익률 제일 높은 거",
                    "수익률 높은 순으로 보여줘",
                    "인기 종목",
                    "랭킹 보여줘",
                    "베스트 종목",
                    "순위 매겨줘",
                    "가장 좋은 거",
                    "제일 잘 나가는 주식",
                    "최근 많이 오른 반도체",
                    "어제 급등한 종목",
                    "이번 주에 떨어진 주식");

    /** 조건을 말하는 평범한 문장. 거절하면 안 된다. */
    private static final List<String> MUST_PASS =
            List.of(
                    "배당 ETF만 보여줘",
                    "반도체 테마로 3등급부터 5등급까지",
                    "안정적인 배당주 위주로 5종목, 변동성 낮은 순서로",
                    "2차전지 관련 펀드 보여줘",
                    "위험등급 높은 순서로 보여줘",
                    "위험등급이 낮은 주식만",
                    "가나다순으로 정렬해줘",
                    "배당 테마 주식과 펀드",
                    "변동성이 큰 순서로",
                    "주식만 10종목",
                    "금융 업종 종목",
                    "인터넷 관련 종목 중에서 4등급 이상",
                    "ETF 말고 주식만",
                    "건설 테마",
                    "바이오 헬스케어 테마를 위험등급 순서대로",
                    "소비재 테마 5종목",
                    "자동차 테마 주식",
                    "위험등급이 가장 높은 것만",
                    "변동성이 가장 낮은 순서로",
                    "위험등급 순서대로 보여줘");

    @Test
    @DisplayName("투자 판단·추천·전망을 요구하는 말은 거절 대상이다")
    void refuses() {
        for (String text : MUST_REFUSE) {
            assertThat(AdviceRequestDetector.isAdviceRequest(text)).as(text).isTrue();
        }
    }

    @Test
    @DisplayName("조건을 말하는 평범한 문장은 거절하지 않는다")
    void doesNotRefuseConditions() {
        for (String text : MUST_PASS) {
            assertThat(AdviceRequestDetector.isAdviceRequest(text)).as(text).isFalse();
        }
    }

    @Test
    @DisplayName("공백·대소문자·null도 오류 없이 처리한다")
    void edgeCases() {
        assertThat(AdviceRequestDetector.isAdviceRequest(null)).isFalse();
        assertThat(AdviceRequestDetector.isAdviceRequest("")).isFalse();
        assertThat(AdviceRequestDetector.isAdviceRequest("   ")).isFalse();
        assertThat(AdviceRequestDetector.isAdviceRequest("어떤 ETF 담을까")).isTrue();
        assertThat(AdviceRequestDetector.isAdviceRequest("etf 추천")).isTrue();
    }
}
