package com.app.maria.domain.investorassessment.entity.choice;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum KnowledgeChoice implements ScoredChoice {
    NONE("거의 모른다", 0),
    LITTLE("조금 안다", 3),
    FAIR("어느 정도 알고 있다", 4),
    GOOD("잘 알고 있다", 5);

    private final String label;
    private final int score;
}
