package com.app.maria.domain.stockpackage.service;

import com.app.maria.domain.domestic.type.DomesticOrderSide;
import com.app.maria.domain.stockpackage.dto.response.GeneratorOrderResponseDTO;

public interface GeneratorOrderService {

    /** 증권사 거래 생성기의 "이 고객이 사거나 판다"를 받아 종목·수량을 정하고 기존 주문 처리로 체결한다. 같은 idempotencyKey는 처음 결과를 돌려준다. */
    GeneratorOrderResponseDTO order(String ciHash, DomesticOrderSide side, String idempotencyKey);
}
