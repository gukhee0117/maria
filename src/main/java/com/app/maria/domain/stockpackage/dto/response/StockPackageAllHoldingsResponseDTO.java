package com.app.maria.domain.stockpackage.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * 내 모든 패키지의 보유 현황. accountAmount(예수금)·buyingPower(예수금에서 가정산을 뺀 주문 가능 금액)는 계좌 하나의 값이라 패키지 목록 밖에 둔다.
 * 패키지가 없어도 이 두 값은 온다(첫 패키지를 만들면서 바로 살 때 필요). 계좌가 없으면 둘 다 null이다.
 */
public record StockPackageAllHoldingsResponseDTO(
        BigDecimal accountAmount,
        BigDecimal buyingPower,
        List<StockPackageHoldingResponseDTO> packages) {}
