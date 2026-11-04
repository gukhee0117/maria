package com.app.maria.domain.stockpackage.service;

import com.app.maria.domain.stockpackage.dto.request.StockOrderRequestDTO;
import com.app.maria.domain.stockpackage.dto.response.StockOrderFeeResponseDTO;
import com.app.maria.domain.stockpackage.dto.response.StockOrderResponseDTO;

public interface StockOrderService {

    /**
     * 패키지에 담은 종목을 시장가로 주문한다. 요청 순서대로 처리하고 종목별로 체결 또는 거부 한 건씩 남긴다. 같은 idempotencyKey로 다시 부르면 새로 체결하지
     * 않고 처음 결과를 돌려준다.
     */
    StockOrderResponseDTO place(Long customerId, Long stockPackageId, StockOrderRequestDTO request);

    /** 오늘 기준 수수료율·증권거래세율. 개설된 RIA 계좌가 있어야 한다. */
    StockOrderFeeResponseDTO getFees(Long customerId);
}
