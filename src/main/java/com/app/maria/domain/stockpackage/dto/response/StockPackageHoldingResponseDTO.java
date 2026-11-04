package com.app.maria.domain.stockpackage.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * 패키지 보유 현황. items에는 패키지에 담은 모든 종목이 들어 있고, 아직 사지 않은 종목은 qty가 0이다. 평가금액·손익은 현재가와 평균매입가로 계산한 단순 값이고
 * 수수료·세금은 빼지 않는다. 보유한 종목의 현재가를 받지 못하면 그 종목의 평가값이 null이고 totalEval·totalProfit도 null이다.
 */
public record StockPackageHoldingResponseDTO(
        Long stockPackageId,
        List<Item> items,
        BigDecimal totalCost,
        BigDecimal totalEval,
        BigDecimal totalProfit,
        BigDecimal accountAmount,
        BigDecimal buyingPower) {

    /**
     * securityTaxable은 팔 때 증권거래세 대상인 종목(코스피·코스닥 주식)인지다. price·previousClose·changePercent는 KIS 현재가
     * 기준이다(받지 못하면 null). changePercent는 전일 종가 대비 등락률(%)이다.
     */
    public record Item(
            Long domesticProductId,
            String ticker,
            String name,
            boolean securityTaxable,
            BigDecimal qty,
            BigDecimal avgPrice,
            BigDecimal cost,
            BigDecimal price,
            BigDecimal previousClose,
            BigDecimal changePercent,
            BigDecimal evalAmount,
            BigDecimal profitAmount,
            BigDecimal profitRatePercent) {}
}
