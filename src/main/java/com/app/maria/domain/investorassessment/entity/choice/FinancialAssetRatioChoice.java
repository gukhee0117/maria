package com.app.maria.domain.investorassessment.entity.choice;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum FinancialAssetRatioChoice implements ScoredChoice {
    UNDER_10("10% 미만", 1),
    FROM_10_TO_30("10% 이상 ~ 30% 미만", 3),
    FROM_30_TO_50("30% 이상 ~ 50% 미만", 4),
    OVER_50("50% 이상", 5);

    private final String label;
    private final int score;
}
