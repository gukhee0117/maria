package com.app.maria.domain.fundflow.type;

/**
 * 자금 이동 내역의 거래 구분. 선언 순서가 같은 날 안에서의 처리 순서다. (원화 확정 → 국내 매도 → 국내 매수 → 인출) 이 순서로 예수금(cashAfter)을
 * 계산하므로 순서를 바꾸면 같은 날 거래 후 예수금이 달라진다.
 */
public enum FundFlowTransactionType {
    KRW_CONFIRMED,
    DOMESTIC_SELL,
    DOMESTIC_BUY,
    WITHDRAWAL;

    /** 종목별 내역(details)을 가지는 구분 */
    public boolean hasProducts() {
        return this == DOMESTIC_SELL || this == DOMESTIC_BUY;
    }
}
