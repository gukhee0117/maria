package com.app.maria.domain.fundflow.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 조정비율 히스토그램 구간. 매퍼의 CASE 코드와 이름이 같아야 한다. 선언 순서가 화면 순서다. */
@Getter
@RequiredArgsConstructor
public enum AdjustRatioBin {
    ZERO("0%"),
    UNDER_25("0 초과 ~ 25%"),
    UNDER_50("25 ~ 50%"),
    UNDER_75("50 ~ 75%"),
    UNDER_100("75% ~ 100% 미만"),
    FULL("100%");

    private final String label;
}
