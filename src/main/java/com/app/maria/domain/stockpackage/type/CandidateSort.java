package com.app.maria.domain.stockpackage.type;

/** 후보 보기 정렬 기준. 모두 사실에 따른 정렬이고, 어떤 순서도 상품의 우열을 뜻하지 않는다. 기본은 NAME이다. */
public enum CandidateSort {
    NAME,
    RISK_GRADE,
    VOLATILITY
}
