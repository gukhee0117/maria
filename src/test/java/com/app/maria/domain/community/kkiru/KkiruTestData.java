package com.app.maria.domain.community.kkiru;

import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 끼루 테스트가 같이 쓰는 답·근거 만들기. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class KkiruTestData {

    /** 근거 하나에 문장 하나인 답. */
    static KkiruAnswer answer(String sentence, String sourceId, String evidenceText) {
        return new KkiruAnswer(
                List.of(new KkiruSentence(sentence, List.of(sourceId))),
                Map.of(sourceId, new Evidence(sourceId, evidenceText, null)));
    }

    /** 위험등급 답: 숫자 2·34가 근거 원문에 있다. */
    static KkiruAnswer riskAnswer() {
        return answer(
                "자체 산정 위험등급은 2등급이고 연 변동성은 34%예요.", "RISK:7", "위험등급 2등급(자체 산정), 3년 기준 연 변동성 34%");
    }

    /** 근거 두 개(공시는 링크 있음)를 각각 한 문장씩 인용한 답. */
    static KkiruAnswer twoSentenceAnswer() {
        return new KkiruAnswer(
                List.of(
                        new KkiruSentence("최근 30일 동안 공시가 3건 올라왔어요.", List.of("DART:1")),
                        new KkiruSentence("자체 산정 위험등급은 2등급이에요.", List.of("RISK:7"))),
                Map.of(
                        "DART:1",
                        new Evidence("DART:1", "최근 30일 공시 3건", "https://dart.fss.or.kr/x"),
                        "RISK:7",
                        new Evidence("RISK:7", "위험등급 2등급(자체 산정)", null)));
    }
}
