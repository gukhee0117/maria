package com.app.maria.domain.community.service;

/** 금칙어 검사 결과. */
public enum TextVerdict {
    PASS,
    /** 스팸일 수도, 질문·피해 글일 수도 있는 표현. 지금은 막지 않고 통과시킨다 */
    REVIEW,
    BLOCK
}
