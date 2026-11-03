package com.app.maria.domain.community.kkiru;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.maria.domain.community.service.CommunityTextGuard;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KkiruAnswerValidatorTest {
    private final KkiruAnswerValidator validator =
            new KkiruAnswerValidator(new CommunityTextGuard());

    private static KkiruAnswer one(
            String sentence, List<String> sourceIds, Map<String, String> evidence) {
        Map<String, Evidence> byId = new java.util.HashMap<>();
        evidence.forEach((id, text) -> byId.put(id, new Evidence(id, text, null)));
        return new KkiruAnswer(List.of(new KkiruSentence(sentence, sourceIds)), byId);
    }

    @Test
    @DisplayName("근거가 있고 숫자가 근거 안에 있는 정상 답은 통과한다")
    void valid() {
        assertThat(validator.validate(KkiruTestData.riskAnswer())).isPresent();
        assertThat(validator.validate(KkiruTestData.twoSentenceAnswer())).isPresent();
    }

    @Test
    @DisplayName("천 단위 쉼표와 전각 숫자는 같은 숫자로 본다")
    void numberNormalization() {
        assertThat(
                        validator.validate(
                                one("영업이익은 1,234억원이에요.", List.of("D"), Map.of("D", "영업이익 1234억원"))))
                .isPresent();
        assertThat(
                        validator.validate(
                                one("영업이익은 １２３４억원이에요.", List.of("D"), Map.of("D", "영업이익 1,234억원"))))
                .isPresent();
    }

    @Test
    @DisplayName("선행 0은 같은 숫자로 본다(01일 = 1일, 000660 = 660), 소수 0.5는 그대로다")
    void leadingZeros() {
        assertThat(
                        validator.validate(
                                one("1월 30일에 접수됐어요.", List.of("D"), Map.of("D", "01월 30일 접수"))))
                .isPresent();
        assertThat(validator.validate(one("종목 000660이에요.", List.of("D"), Map.of("D", "종목 660"))))
                .isPresent();
        assertThat(validator.validate(one("변동성은 0.5%예요.", List.of("D"), Map.of("D", "변동성 0.5%"))))
                .isPresent();
        // 0.5와 5는 다르다: 선행 0을 걷어도 소수점 앞의 0은 남는다
        assertThat(validator.validate(one("변동성은 5%예요.", List.of("D"), Map.of("D", "변동성 0.5%"))))
                .isEmpty();
    }

    @Test
    @DisplayName("근거에 없는 숫자가 문장에 있으면 답을 버린다")
    void numberNotInEvidence() {
        assertThat(validator.validate(one("공시가 5건 올라왔어요.", List.of("D"), Map.of("D", "공시 3건"))))
                .isEmpty();
        assertThat(validator.validate(one("변동성은 34%예요.", List.of("D"), Map.of("D", "변동성 34.2%"))))
                .isEmpty();
    }

    @Test
    @DisplayName("다른 문장의 근거에만 있는 숫자는 인정하지 않는다(인용한 근거 안에 있어야 한다)")
    void numberMustBeInCitedSource() {
        KkiruAnswer answer =
                new KkiruAnswer(
                        List.of(new KkiruSentence("공시가 7건이에요.", List.of("A"))),
                        Map.of(
                                "A", new Evidence("A", "공시 3건", null),
                                "B", new Evidence("B", "공시 7건", null)));

        assertThat(validator.validate(answer)).isEmpty();
    }

    @Test
    @DisplayName("근거가 없거나, 모으지 않은 근거 id를 인용하면 답을 버린다")
    void sourceRules() {
        assertThat(validator.validate(one("공시가 올라왔어요.", List.of(), Map.of("D", "공시")))).isEmpty();
        assertThat(validator.validate(one("공시가 올라왔어요.", null, Map.of("D", "공시")))).isEmpty();
        assertThat(validator.validate(one("공시가 올라왔어요.", List.of("X"), Map.of("D", "공시"))))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "지금 사세요",
                "이 종목은 오를 것 같아요",
                "목표가는 높게 봐요",
                "강력 추천하는 종목이에요",
                "수익 보장돼요",
                "유망해 보여요",
                "지 금 사 세 요 (공백으로 쪼갬)"
            })
    @DisplayName("매수 권유·전망·평가 표현이 든 문장이 하나라도 있으면 답을 버린다")
    void opinionPhrases(String sentence) {
        assertThat(validator.validate(one(sentence, List.of("D"), Map.of("D", "공시")))).isEmpty();
    }

    @Test
    @DisplayName("욕설 같은 금칙어가 든 문장도 버린다")
    void bannedWords() {
        assertThat(validator.validate(one("이 종목 산 놈들 다 개새끼", List.of("D"), Map.of("D", "공시"))))
                .isEmpty();
    }

    @Test
    @DisplayName("한 문장이라도 틀리면 답 전체를 버린다")
    void oneBadSentenceDropsAnswer() {
        KkiruAnswer answer =
                new KkiruAnswer(
                        List.of(
                                new KkiruSentence("공시가 3건 올라왔어요.", List.of("D")),
                                new KkiruSentence("지금 사세요.", List.of("D"))),
                        Map.of("D", new Evidence("D", "공시 3건", null)));

        assertThat(validator.validate(answer)).isEmpty();
    }

    @Test
    @DisplayName("비었거나 null인 답은 버린다")
    void emptyShapes() {
        assertThat(validator.validate(null)).isEmpty();
        assertThat(validator.validate(new KkiruAnswer(List.of(), Map.of()))).isEmpty();
        assertThat(validator.validate(new KkiruAnswer(null, Map.of()))).isEmpty();
        assertThat(
                        validator.validate(
                                new KkiruAnswer(
                                        List.of(new KkiruSentence(" ", List.of("D"))),
                                        Map.of("D", new Evidence("D", "x", null)))))
                .isEmpty();
        assertThat(
                        validator.validate(
                                new KkiruAnswer(
                                        List.of(new KkiruSentence("문장", List.of("D"))), null)))
                .isEmpty();
    }

    // ---------- 주장 확인 판정 모순 검사 ----------

    private static KkiruAnswer claimAnswer(String claim, String official, String verdictSentence) {
        return new KkiruAnswer(
                List.of(new KkiruSentence(verdictSentence, List.of("claim:post:1", "fin:1"))),
                Map.of(
                        "claim:post:1", new Evidence("claim:post:1", claim, null, true),
                        "fin:1", new Evidence("fin:1", official, null)));
    }

    private static final String OFFICIAL = "우리금융지주의 2026년 반기(2분기) 연결 영업이익은 1조 3,157억원입니다.";

    @Test
    @DisplayName("주장의 숫자가 공식 자료에 모두 있는데 '다릅니다'라고 하면 틀린 판정이라 버린다(실제 호출에서 나온 사례)")
    void claimNumbersMatchButSaysDiffer() {
        KkiruAnswer answer =
                claimAnswer(
                        "우리금융지주 영업이익이 1조 3,157억원이었다는데 사실인가요?",
                        OFFICIAL,
                        "주장은 공식 자료와 다릅니다. 영업이익은 1조 3,157억원이 아니라 1조 3,157억원입니다.");

        assertThat(validator.validate(answer)).isEmpty();
    }

    @Test
    @DisplayName("주장의 숫자가 공식 자료에 없는데 '일치합니다'라고 하면 버린다")
    void claimNumberMissingButSaysMatch() {
        KkiruAnswer answer =
                claimAnswer(
                        "우리금융지주 영업이익이 1조 8,000억원이었다는데 사실인가요?",
                        OFFICIAL,
                        "주장은 공식 자료와 일치합니다. 영업이익은 1조 3,157억원입니다.");

        assertThat(validator.validate(answer)).isEmpty();
    }

    @Test
    @DisplayName("숫자와 모순되지 않는 판정은 통과한다: 같으면 일치, 다르면 다릅니다·일치하지 않습니다")
    void consistentVerdictsPass() {
        String sameClaim = "우리금융지주 영업이익이 1조 3,157억원이었다는데 사실인가요?";
        String otherClaim = "우리금융지주 영업이익이 1조 8,000억원이었다는데 사실인가요?";

        assertThat(
                        validator.validate(
                                claimAnswer(
                                        sameClaim, OFFICIAL, "주장은 공식 자료와 일치합니다. 1조 3,157억원입니다.")))
                .isPresent();
        assertThat(
                        validator.validate(
                                claimAnswer(
                                        otherClaim,
                                        OFFICIAL,
                                        "주장은 공식 자료와 다릅니다. 영업이익은 1조 3,157억원입니다.")))
                .isPresent();
        assertThat(
                        validator.validate(
                                claimAnswer(
                                        otherClaim,
                                        OFFICIAL,
                                        "주장은 공식 자료와 일치하지 않습니다. 영업이익은 1조 3,157억원입니다.")))
                .isPresent();
    }

    @Test
    @DisplayName("'공식 자료로 확인되지 않습니다'는 숫자와 상관없이 허용하고, 숫자가 없는 주장은 검사하지 않는다")
    void unknownVerdictAndNumberlessClaim() {
        assertThat(
                        validator.validate(
                                claimAnswer(
                                        "우리금융지주 영업이익이 1조 3,157억원이었다는데 사실인가요?",
                                        OFFICIAL,
                                        "주장은 공식 자료로 확인되지 않습니다. 영업이익은 1조 3,157억원입니다.")))
                .isPresent();
        assertThat(
                        validator.validate(
                                claimAnswer(
                                        "우리금융지주가 큰 이익을 냈다는데 사실인가요?",
                                        OFFICIAL,
                                        "주장은 공식 자료와 다릅니다. 영업이익은 1조 3,157억원입니다.")))
                .isPresent();
    }

    @Test
    @DisplayName("주장 근거가 없는 일반 답은 이 검사를 받지 않는다")
    void nonClaimAnswersAreUnaffected() {
        assertThat(validator.validate(KkiruTestData.riskAnswer())).isPresent();
    }
}
