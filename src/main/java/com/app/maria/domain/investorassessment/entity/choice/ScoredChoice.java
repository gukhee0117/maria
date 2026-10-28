package com.app.maria.domain.investorassessment.entity.choice;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/** 설문 보기. 보기 문구와 배점을 가진다. 요청에서는 enum 이름 문자열로만 받는다. */
@JsonDeserialize(using = ChoiceNameDeserializer.class)
public interface ScoredChoice {
    /** enum 상수 이름. 제출 요청과 문항 조회 응답이 같은 값을 쓴다. */
    String name();

    String label();

    int score();
}
