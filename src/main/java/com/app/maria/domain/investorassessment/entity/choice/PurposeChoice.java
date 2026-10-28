package com.app.maria.domain.investorassessment.entity.choice;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum PurposeChoice implements ScoredChoice {
    GROW_ASSETS("자산 늘리기", 5),
    LIVING_EXPENSES("생활비 마련", 2),
    REPAY_DEBT("채무상환", -5);

    private final String label;
    private final int score;
}
