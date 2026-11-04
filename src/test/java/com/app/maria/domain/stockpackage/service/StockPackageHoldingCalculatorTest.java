package com.app.maria.domain.stockpackage.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.maria.domain.domestic.type.DomesticOrderSide;
import com.app.maria.domain.stockpackage.service.StockPackageHoldingCalculator.Position;
import com.app.maria.domain.stockpackage.vo.StockOrderVO;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class StockPackageHoldingCalculatorTest {
    private static StockOrderVO order(
            long product, DomesticOrderSide side, String qty, String price) {
        return StockOrderVO.builder()
                .domesticProductId(product)
                .ticker("T" + product)
                .name("종목" + product)
                .side(side)
                .qty(new BigDecimal(qty))
                .price(new BigDecimal(price))
                .build();
    }

    @Test
    void 매수를_이어서_하면_체결가_가중평균이다() {
        List<Position> p =
                StockPackageHoldingCalculator.positions(
                        List.of(
                                order(1, DomesticOrderSide.BUY, "10", "100000"),
                                order(1, DomesticOrderSide.BUY, "10", "120000")));

        assertThat(p).hasSize(1);
        assertThat(p.get(0).qty()).isEqualByComparingTo("20");
        assertThat(p.get(0).avgPrice()).isEqualByComparingTo("110000");
    }

    @Test
    void 일부_매도하면_수량만_줄고_평균단가는_그대로다() {
        List<Position> p =
                StockPackageHoldingCalculator.positions(
                        List.of(
                                order(1, DomesticOrderSide.BUY, "20", "110000"),
                                order(1, DomesticOrderSide.SELL, "5", "130000")));

        assertThat(p.get(0).qty()).isEqualByComparingTo("15");
        assertThat(p.get(0).avgPrice()).isEqualByComparingTo("110000");
    }

    @Test
    void 전량_팔면_목록에서_빠지고_다시_사면_평균단가를_새로_시작한다() {
        assertThat(
                        StockPackageHoldingCalculator.positions(
                                List.of(
                                        order(1, DomesticOrderSide.BUY, "5", "100000"),
                                        order(1, DomesticOrderSide.SELL, "5", "110000"))))
                .isEmpty();

        List<Position> again =
                StockPackageHoldingCalculator.positions(
                        List.of(
                                order(1, DomesticOrderSide.BUY, "5", "100000"),
                                order(1, DomesticOrderSide.SELL, "5", "110000"),
                                order(1, DomesticOrderSide.BUY, "2", "70000")));
        assertThat(again.get(0).qty()).isEqualByComparingTo("2");
        assertThat(again.get(0).avgPrice()).isEqualByComparingTo("70000");
    }

    @Test
    void 패키지에서_산_것보다_많이_팔았어도_마이너스로_만들지_않는다() {
        assertThat(
                        StockPackageHoldingCalculator.positions(
                                List.of(
                                        order(1, DomesticOrderSide.BUY, "3", "100000"),
                                        order(1, DomesticOrderSide.SELL, "10", "100000"))))
                .isEmpty();
    }

    @Test
    void 종목이_여럿이면_처음_산_순서대로_돌려준다() {
        List<Position> p =
                StockPackageHoldingCalculator.positions(
                        List.of(
                                order(2, DomesticOrderSide.BUY, "1", "1000"),
                                order(1, DomesticOrderSide.BUY, "1", "2000")));

        assertThat(p).extracting(Position::domesticProductId).containsExactly(2L, 1L);
    }

    @Test
    void 거래세_대상_여부는_주문의_상품_정보에서_이어받는다() {
        StockOrderVO o = order(1, DomesticOrderSide.BUY, "1", "1000");
        o.setProductType(com.app.maria.domain.domestic.type.Type.STOCK);
        o.setMarket("KOSDAQ");
        StockOrderVO etf = order(2, DomesticOrderSide.BUY, "1", "1000");
        etf.setProductType(com.app.maria.domain.domestic.type.Type.FUND);
        etf.setMarket("KOSPI");

        List<Position> p = StockPackageHoldingCalculator.positions(List.of(o, etf));

        assertThat(p.get(0).taxable()).isTrue();
        assertThat(p.get(1).taxable()).isFalse();
    }
}
