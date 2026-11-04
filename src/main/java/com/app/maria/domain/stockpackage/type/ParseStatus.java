package com.app.maria.domain.stockpackage.type;

/** 말로 조건 찾기의 결과 종류. 화면은 이 값으로 필터 적용·안내 문구를 가른다. */
public enum ParseStatus {
    /** 조건을 찾았다. */
    PARSED,
    /** 투자 판단·추천을 요구하는 말이라 조건으로 바꾸지 않았다. */
    ADVICE_REFUSED,
    /** 조건으로 바꿀 내용이 없거나 이해하지 못했다. */
    NOT_UNDERSTOOD,
    /** AI를 쓸 수 없어 변환하지 못했다. */
    UNAVAILABLE
}
