package com.app.maria.domain.stockpackage.service;

import com.app.maria.domain.domestic.type.DomesticOrderSide;
import com.app.maria.domain.stockpackage.vo.StockOrderVO;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 한 패키지로 체결된 주문을 오래된 순으로 합산해 종목별 보유 수량과 평균매입가를 만든다. 보유는 따로 저장하지 않는다(계좌 보유는 여러 패키지와 직접 매매가 섞여 있어서
 * 패키지 몫을 나눌 수 없다). 평균매입가는 계좌 잔고와 같은 이동평균(체결가 기준, 수수료 제외)이다.
 *
 * <p>계좌 보유 기준으로 판 수량이 이 패키지에서 산 수량보다 많으면 패키지 보유는 0으로 둔다(마이너스로 만들지 않는다).
 *
 * <p>이 수량은 화면 표시뿐 아니라 패키지 매도 한도로도 쓴다({@code StockOrderServiceImpl}). 위 0 하한 규칙을 바꾸면 매도할 수 있는 수량도 같이
 * 바뀐다. 패키지 매도가 이 수량 안으로 제한된 뒤로는 새로 0 아래로 내려가는 경우가 생기지 않는다.
 */
final class StockPackageHoldingCalculator {
    private StockPackageHoldingCalculator() {}

    record Position(
            Long domesticProductId,
            String ticker,
            String name,
            boolean taxable,
            BigDecimal qty,
            BigDecimal avgPrice) {}

    /** 보유 수량이 0보다 큰 종목만, 처음 산 순서대로 돌려준다. */
    static List<Position> positions(List<StockOrderVO> executedAscending) {
        Map<Long, Position> byProduct = new LinkedHashMap<>();
        for (StockOrderVO o : executedAscending) {
            Position p =
                    byProduct.getOrDefault(
                            o.getDomesticProductId(),
                            new Position(
                                    o.getDomesticProductId(),
                                    o.getTicker(),
                                    o.getName(),
                                    StockOrderCalculator.isTaxable(
                                            o.getProductType(), o.getMarket()),
                                    BigDecimal.ZERO,
                                    BigDecimal.ZERO));
            if (o.getSide() == DomesticOrderSide.BUY) {
                BigDecimal avg =
                        StockOrderCalculator.averageAfterBuy(
                                p.qty(), p.avgPrice(), o.getQty(), o.getPrice());
                p =
                        new Position(
                                p.domesticProductId(),
                                p.ticker(),
                                p.name(),
                                p.taxable(),
                                p.qty().add(o.getQty()),
                                avg);
            } else {
                BigDecimal left = p.qty().subtract(o.getQty()).max(BigDecimal.ZERO);
                BigDecimal avg = left.signum() == 0 ? BigDecimal.ZERO : p.avgPrice();
                p =
                        new Position(
                                p.domesticProductId(),
                                p.ticker(),
                                p.name(),
                                p.taxable(),
                                left,
                                avg);
            }
            byProduct.put(o.getDomesticProductId(), p);
        }
        List<Position> held = new ArrayList<>();
        for (Position p : byProduct.values()) {
            if (p.qty().signum() > 0) {
                held.add(p);
            }
        }
        return held;
    }
}
