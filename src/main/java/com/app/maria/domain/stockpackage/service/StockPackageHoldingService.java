package com.app.maria.domain.stockpackage.service;

import com.app.maria.domain.stockpackage.dto.response.StockOrderResultDTO;
import com.app.maria.domain.stockpackage.dto.response.StockPackageAllHoldingsResponseDTO;
import com.app.maria.domain.stockpackage.dto.response.StockPackageHoldingResponseDTO;
import java.util.List;

public interface StockPackageHoldingService {

    /** 이 패키지로 체결한 주문을 합산한 보유 현황과 현재가 기준 평가. 담은 종목은 사지 않았어도 수량 0으로 나온다. */
    StockPackageHoldingResponseDTO getHoldings(Long customerId, Long stockPackageId);

    /**
     * 내 모든 패키지의 보유 현황. 현재가는 종목 합집합으로 한 번에 조회한다. 패키지는 목록 조회와 같은 순서(최근 만든 순)다. 패키지가 없어도 예수금·주문 가능 금액은
     * 온다.
     */
    StockPackageAllHoldingsResponseDTO getAllHoldings(Long customerId);

    /** 주문 내역(체결·거부), 최신순. stockPackageId가 null이면 내 모든 패키지. */
    List<StockOrderResultDTO> getOrders(Long customerId, Long stockPackageId);
}
