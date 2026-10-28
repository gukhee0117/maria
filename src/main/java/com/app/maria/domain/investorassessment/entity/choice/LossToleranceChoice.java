package com.app.maria.domain.investorassessment.entity.choice;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum LossToleranceChoice implements ScoredChoice {
    NO_LOSS("원금을 잃을 수는 없다", 0),
    UP_TO_10("-10% 손실까지 참을 수 있다", 3),
    UP_TO_20("-20% 손실까지 참을 수 있다", 3),
    UP_TO_50("-50% 손실까지 참을 수 있다", 4),
    UP_TO_70("-70% 손실까지 참을 수 있다", 4),
    ANY_LOSS("전체손실도 참을 수 있다", 5);

    private final String label;
    private final int score;
}
