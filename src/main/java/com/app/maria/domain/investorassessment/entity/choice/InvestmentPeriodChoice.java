package com.app.maria.domain.investorassessment.entity.choice;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum InvestmentPeriodChoice implements ScoredChoice {
    WITHIN_1_YEAR("1년 안에 그만둘 것이다.", 1),
    WITHIN_3_YEARS("3년 안에 그만둘 것이다.", 3),
    OVER_3_YEARS("3년 이상 계속할 것이다.", 5);

    private final String label;
    private final int score;
}
