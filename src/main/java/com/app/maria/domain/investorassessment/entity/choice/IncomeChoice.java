package com.app.maria.domain.investorassessment.entity.choice;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum IncomeChoice implements ScoredChoice {
    UNDER_30M("3천만원 미만", 1),
    FROM_30M_TO_50M("3천만원 이상 5천만원 미만", 2),
    FROM_50M_TO_70M("5천만원 이상 7천만원 미만", 3),
    FROM_70M_TO_100M("7천만원 이상 1억원 미만", 4),
    OVER_100M("1억원 이상", 5);

    private final String label;
    private final int score;
}
