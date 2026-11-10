package com.app.maria.domain.stockpackage.type;

import lombok.Getter;

/**
 * domestic_order.reject_reason 값. 고객에게 보이는 문장은 사실만 알리고, 무엇을 하라는 말은 하지 않는다. 앞의 두 값은 시드와 같은 이름이고 나머지는
 * 앱이 추가했다.
 */
@Getter
public enum StockOrderRejectReason {
    NOT_ELIGIBLE("RIA 계좌에서 살 수 없는 상품이에요."),
    INSUFFICIENT_CASH("주문할 수 있는 예수금이 부족해요."),
    INSUFFICIENT_QTY("팔 수 있는 보유 수량이 부족해요."),
    PRICE_UNAVAILABLE("현재가를 받지 못했어요."),
    TRADE_RESTRICTED("거래가 제한되거나 정지된 종목이에요."),
    WEIGHT_LIMIT_EXCEEDED("패키지에서 정한 목표 비중을 넘는 주문이에요.");

    private final String message;

    StockOrderRejectReason(String message) {
        this.message = message;
    }
}
