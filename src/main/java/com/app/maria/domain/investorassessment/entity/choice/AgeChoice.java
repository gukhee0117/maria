package com.app.maria.domain.investorassessment.entity.choice;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum AgeChoice implements ScoredChoice {
    UNDER_19("만 19세 미만", 1),
    FROM_19_TO_29("만 19세 이상 ~ 만 30세 미만", 3),
    FROM_30_TO_64("만 30세 이상 ~ 만 65세 미만", 5),
    FROM_65_TO_79("만 65세 이상 ~ 만 80세 미만", 2),
    OVER_80("만 80세 이상", 1);

    private final String label;
    private final int score;
}
