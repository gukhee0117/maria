package com.app.maria.domain.stockpackage.service;

import com.app.maria.domain.stockpackage.dto.response.StockTendencyResponseDTO;

public interface StockTendencyService {
    /**
     * 설문 투자성향과 거래에서 본 경향을 함께 돌려준다. 거래에서 본 경향은 RIA 계좌의 체결 주문으로 계산한 참고 정보이고, 체결된 매수가 {@link
     * StockTendencyServiceImpl#MIN_BUY_COUNT}건보다 적으면 계산하지 않는다. 두 결과가 달라도 어느 쪽을 따르라는 말은 하지 않고 차이를
     * 사실로만 보여준다.
     */
    StockTendencyResponseDTO getTendency(Long customerId);
}
