package com.app.maria.domain.news.service;

import java.text.Normalizer;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Only reviewed article-question intents reach the model; raw user instructions/history never do.
 */
public final class NewsQuestionPolicy {
    public static final String VERSION = "reviewed-news-question-policy-v2";
    public static final String REFUSAL = "매수·매도 추천, 매매 시점·가격 등 투자 판단에 관한 조언은 제공하지 않습니다.";
    public static final String OFF_TOPIC =
            "저는 금융뉴스를 쉽고 똑똑하게 알려드리는 끼루예요!\n\n"
                    + "지금은 선택하신 뉴스와 관련된 질문에만 답변할 수 있어요.\n\n"
                    + "뉴스 내용 중 궁금한 점이 있다면 편하게 물어봐 주세요!";
    private static final String EASY = "이 기사를 쉽게 설명해 줘";
    private static final String NUMBERS = "기사에 나온 숫자만 정리해줘";
    private static final String STOCKS = "관련 종목이 뭐야?";
    private static final String RISKS = "주의할 위험은 뭐야?";
    private static final Map<String, String> ALLOWED =
            Map.ofEntries(
                    Map.entry("이기사를쉽게설명해줘", EASY), Map.entry("쉽게다시설명해줘", EASY),
                    Map.entry("쉽게설명해줘", EASY), Map.entry("설명", EASY),
                    Map.entry("이기사요약해줘", EASY), Map.entry("기사요약해줘", EASY),
                    Map.entry("기사에나온숫자만정리해줘", NUMBERS), Map.entry("기사의수치를정리해줘", NUMBERS),
                    Map.entry("관련종목이뭐야", STOCKS), Map.entry("관련종목은뭐야", STOCKS),
                    Map.entry("주의할위험은뭐야", RISKS), Map.entry("이기사의위험요인은뭐야", RISKS));
    private static final Pattern ADVICE =
            Pattern.compile(
                    "매수(?:를)?(?:추천|권장|권유)|매수하(?:세요|셔야|해야|는것을(?:추천|권장|권유)|는게좋|는것이좋|는편이유리)|매수하시(?:는것을(?:추천|권장|권유)|는게좋|는것이좋|는편이유리)"
                            + "|매도(?:를)?(?:추천|권장|권유)|매도하(?:세요|셔야|해야|는것을(?:추천|권장|권유)|는게좋|는것이좋|는편이유리)|매도하시(?:는것을(?:추천|권장|권유)|는게좋|는것이좋|는편이유리)"
                            + "|사(?:세요|셔야|야(?:합니다|해요|해|한다|할)|도좋|는게좋|는것이좋|는편이유리)"
                            + "|팔(?:세요|셔야|아야(?:합니다|해요|해|한다|할)?|아도좋|는게좋|는것이좋|는편이유리)"
                            + "|(?:주식|종목|투자비중|보유비중|주식비중|종목비중|비중)(?:을|를|은|는)?(?:늘리|줄이)(?:세요|셔야|는게좋|는것이좋|길추천|기를추천)"
                            + "|(?:지금|현재)?(?:매수신호|매도신호|매수타이밍|매도타이밍)"
                            + "|수익보장"
                            + "|shouldbuy|shouldsell|recommendbuy|recommendsell");
    private static final Pattern ADVICE_REQUEST =
            Pattern.compile(
                    "(?:주식|종목).{0,20}(?:살까|사도될까|사는게좋|사는것이좋|사는게어때|사는건어때|사면어때|살만할까|사도괜찮|매수해도될까|팔까|팔아도될까|파는게좋|파는것이좋|파는게어때|팔면어때|팔아도괜찮|매도해도될까|추천해)"
                            + "|살까|팔까|사도될까|팔아도될까|사야할까|팔아야할까|사야하나|살지말지|팔지말지|팔지말까|사도괜찮|매수하는게|매도하는게"
                            + "|매수|매도|손절|익절|목표가|적정가|매매시점|진입시점|청산시점|투자전문가|투자상담|투자조언|종목추천|투자추천"
                            + "|shouldibuy|shouldisell|shouldbuystock|shouldsellstock");

    private NewsQuestionPolicy() {}

    public static Optional<String> canonicalQuestion(String question) {
        return Optional.ofNullable(ALLOWED.get(normalize(question)));
    }

    public static boolean containsAdvice(String answer) {
        return ADVICE.matcher(normalize(answer)).find();
    }

    public static String refusalFor(String question) {
        return ADVICE_REQUEST.matcher(normalize(question)).find() ? REFUSAL : OFF_TOPIC;
    }

    private static String normalize(String value) {
        var result = new StringBuilder();
        Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(java.util.Locale.ROOT)
                .codePoints()
                .filter(Character::isLetterOrDigit)
                .forEach(result::appendCodePoint);
        return result.toString();
    }
}
