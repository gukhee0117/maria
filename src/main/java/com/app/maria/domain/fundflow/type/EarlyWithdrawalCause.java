package com.app.maria.domain.fundflow.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 1년 미경과 원금 인출의 원인. 선언 순서가 응답의 고정 순서다. */
@Getter
@RequiredArgsConstructor
public enum EarlyWithdrawalCause {
    CLOSURE_FORCED("해지 강제 인출"),
    OTHER("그 밖의 인출");

    private final String label;
}
