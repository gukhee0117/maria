package com.app.maria.domain.investorassessment.entity.choice;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum DerivativeExperienceChoice implements ScoredChoice {
    // 파생상품을 해본 적 없는 고객이 답할 수 있도록 프로젝트에서 추가한 보기
    NONE("경험 없음", 0),
    UNDER_1_YEAR("1년 미만", 1),
    FROM_1_TO_3_YEARS("3년 미만", 3),
    OVER_3_YEARS("3년 이상", 5);

    private final String label;
    private final int score;
}
