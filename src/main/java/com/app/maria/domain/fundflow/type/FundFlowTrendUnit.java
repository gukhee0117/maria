package com.app.maria.domain.fundflow.type;

/** 잔액 추이 차트의 기간 단위. 첫 원화 확정일부터 업무일까지 3개월 미만이면 주, 이상이면 월. */
public enum FundFlowTrendUnit {
    WEEK,
    MONTH
}
