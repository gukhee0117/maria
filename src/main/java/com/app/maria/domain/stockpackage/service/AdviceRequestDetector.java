package com.app.maria.domain.stockpackage.service;

import java.util.regex.Pattern;

/**
 * 투자 판단·추천을 요구하는 말을 알아본다. 고객의 말을 AI에게 보내기 전에 걸러 비용을 아끼고 한 번 더 막는 용도이다.
 *
 * <p>이 목록이 전부를 잡는다고 가정하지 않는다. 놓친 말은 AI가 투자 판단 요청(ADVICE_REQUEST)으로 분류하고, 그것도 놓치면 AI 응답은 조건 값으로만 쓰이는
 * 구조라 고객에게는 검증된 조건이나 고정 문구만 나간다. 반대로 조건을 말하는 평범한 문장을 거절하지 않도록 오탐도 테스트로 막는다.
 */
public final class AdviceRequestDetector {
    private static final Pattern ADVICE =
            Pattern.compile(
                    String.join(
                            "|",
                            // 사고 파는 판단을 묻는 말
                            "사야|사도|살까|살지|살만|살 만|사볼까|사라|사줘|팔아야|팔까|팔아도|팔지|팔라",
                            "담아야|담을까|담아도|담지|담으라|담아줘|넣어야|넣을까|넣어도",
                            "(매수|매도)\\s*(해야|해도|할까|할지|하면|해줘|해라)",
                            "(투자|가입)\\s*(해도|할까|해야|할지)",
                            // 추천·권유를 요구하는 말
                            "추천|권유|권해",
                            // 무엇을 고를지 묻는 말
                            "(뭐|뭘|무엇을?)\\s*(사|담|넣|투자|팔)",
                            "(어떤|어느)\\s*(종목|주식|etf|펀드|걸)\\S*\\s*(사|담|투자|좋)",
                            "(좋은|괜찮은|유망한|돈\\s*되는|대박)\\s*(종목|주식|etf|펀드|거|것|놈)",
                            "하나만\\s*(골라|알려|찍어)",
                            // 수익률·인기 같은 성과 순위를 요구하는 말(이 서비스는 순위를 매기지 않는다)
                            "수익률|인기|랭킹|순위|베스트",
                            "(제일|가장)\\s*(좋|괜찮|잘\\s*나가|많이\\s*오)",
                            // 가격·수익 전망을 묻는 말
                            "오를|오르|오른|올라|올랐|떨어질|떨어진|내릴|폭등|폭락|급등|급락|상승할|하락할|전망|예상",
                            "수익.{0,3}(날|낼|나|보장|확실)"),
                    Pattern.CASE_INSENSITIVE);

    private AdviceRequestDetector() {}

    public static boolean isAdviceRequest(String text) {
        return text != null && ADVICE.matcher(text).find();
    }
}
