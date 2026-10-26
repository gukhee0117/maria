package com.app.maria.domain.customer.entity;

/** 투자성향. 진단 결과 5단계와 아직 진단하지 않은 UNASSIGNED로 이루어진다. */
public enum InvestorType {
    STABLE,
    CONSERVATIVE,
    NEUTRAL,
    ACTIVE,
    AGGRESSIVE,
    UNASSIGNED
}
