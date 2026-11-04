package com.app.maria.domain.stockpackage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.maria.domain.domestic.type.DomesticOrderSide;
import com.app.maria.domain.domestic.type.DomesticStockStatus;
import com.app.maria.domain.domestic.type.Type;
import com.app.maria.domain.tax.dto.TaxRuleDTO;
import com.app.maria.domain.tax.type.TaxRuleType;
import com.app.maria.global.exception.AppException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 수수료·거래세 기대값은 data-generator의 앱 변경 요청서(app-change-request-fee-tax.md §5)와 같다. */
class StockOrderCalculatorTest {
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate FOREVER = LocalDate.of(9999, 12, 31);

    private static final List<TaxRuleDTO> RULES =
            List.of(
                    rule(1L, TaxRuleType.COMMISSION_RIA, "0.0040"),
                    rule(2L, TaxRuleType.COMMISSION_BASE, "0.0150"),
                    rule(3L, TaxRuleType.COMMISSION_RIA_YEARS, "1.0000"),
                    rule(4L, TaxRuleType.SEC_TX_TAX_STOCK, "0.2000"));

    private static TaxRuleDTO rule(long id, TaxRuleType type, String value) {
        return new TaxRuleDTO(id, type, new BigDecimal(value), FROM, FOREVER);
    }

    private static BigDecimal won(long v) {
        return BigDecimal.valueOf(v);
    }

    @Test
    void 체결금액은_수량곱하기_체결가를_원미만_반올림한다() {
        assertThat(StockOrderCalculator.cost(won(3), new BigDecimal("1000.5")))
                .isEqualByComparingTo("3002");
        assertThat(StockOrderCalculator.cost(won(10), won(98000))).isEqualByComparingTo("980000");
    }

    @Test
    void 개설일부터_1년_안이면_우대_수수료다() {
        BigDecimal fee =
                StockOrderCalculator.commission(
                        won(10_000_000),
                        LocalDate.of(2026, 3, 24),
                        LocalDate.of(2026, 11, 18),
                        RULES);
        assertThat(fee).isEqualByComparingTo("400");
    }

    @Test
    void 개설_1년_뒤에는_기본_수수료다() {
        BigDecimal fee =
                StockOrderCalculator.commission(
                        won(10_000_000),
                        LocalDate.of(2026, 3, 24),
                        LocalDate.of(2027, 6, 1),
                        RULES);
        assertThat(fee).isEqualByComparingTo("1500");
    }

    @Test
    void 우대는_개설_1년_후_같은_날짜부터_끝난다() {
        LocalDate opened = LocalDate.of(2026, 3, 24);
        assertThat(
                        StockOrderCalculator.commission(
                                won(10_000_000), opened, LocalDate.of(2027, 3, 23), RULES))
                .isEqualByComparingTo("400");
        assertThat(
                        StockOrderCalculator.commission(
                                won(10_000_000), opened, LocalDate.of(2027, 3, 24), RULES))
                .isEqualByComparingTo("1500");
    }

    @Test
    void 수수료는_원미만을_버린다() {
        LocalDate opened = LocalDate.of(2026, 3, 24);
        LocalDate day = LocalDate.of(2026, 11, 18);
        assertThat(StockOrderCalculator.commission(won(123_456), opened, day, RULES))
                .isEqualByComparingTo("4"); // 4.938
        assertThat(StockOrderCalculator.commission(won(24_999), opened, day, RULES))
                .isEqualByComparingTo("0"); // 0.99996
    }

    @Test
    void 거래세는_코스피_코스닥_주식_매도만_낸다() {
        LocalDate day = LocalDate.of(2026, 11, 18);
        BigDecimal cost = won(10_000_000);
        assertThat(
                        StockOrderCalculator.securitiesTax(
                                DomesticOrderSide.SELL, Type.STOCK, "KOSPI", cost, day, RULES))
                .isEqualByComparingTo("20000");
        assertThat(
                        StockOrderCalculator.securitiesTax(
                                DomesticOrderSide.SELL, Type.STOCK, "KOSDAQ", cost, day, RULES))
                .isEqualByComparingTo("20000");
        assertThat(
                        StockOrderCalculator.securitiesTax(
                                DomesticOrderSide.BUY, Type.STOCK, "KOSPI", cost, day, RULES))
                .isEqualByComparingTo("0");
        assertThat(
                        StockOrderCalculator.securitiesTax(
                                DomesticOrderSide.SELL, Type.FUND, "KOSPI", cost, day, RULES))
                .isEqualByComparingTo("0");
        assertThat(
                        StockOrderCalculator.securitiesTax(
                                DomesticOrderSide.SELL, Type.STOCK, "KONEX", cost, day, RULES))
                .isEqualByComparingTo("0");
    }

    @Test
    void 규칙이_없는_날짜면_예외다() {
        assertThatThrownBy(
                        () ->
                                StockOrderCalculator.commission(
                                        won(1_000_000),
                                        LocalDate.of(2025, 1, 1),
                                        LocalDate.of(2025, 6, 1),
                                        RULES))
                .isInstanceOf(AppException.class);
    }

    @Test
    void 예수금_변동은_매수는_마이너스_매도는_플러스_순액이다() {
        assertThat(
                        StockOrderCalculator.netAmount(
                                DomesticOrderSide.BUY, won(1_000_000), won(40), won(0)))
                .isEqualByComparingTo("-1000040");
        assertThat(
                        StockOrderCalculator.netAmount(
                                DomesticOrderSide.SELL, won(400_000), won(16), won(800)))
                .isEqualByComparingTo("399184");
    }

    @Test
    void 평균매입가는_체결가_가중평균이고_보유가_없으면_체결가로_시작한다() {
        assertThat(StockOrderCalculator.averageAfterBuy(won(10), won(90000), won(10), won(100000)))
                .isEqualByComparingTo("95000");
        assertThat(
                        StockOrderCalculator.averageAfterBuy(
                                BigDecimal.ZERO, BigDecimal.ZERO, won(5), won(70000)))
                .isEqualByComparingTo("70000");
    }

    @Test
    void 매도_뒤_상태는_전량이면_SOLD_OUT_남으면_PARTIALLY_SOLD다() {
        assertThat(StockOrderCalculator.statusAfterSell(BigDecimal.ZERO))
                .isEqualTo(DomesticStockStatus.SOLD_OUT);
        assertThat(StockOrderCalculator.statusAfterSell(won(3)))
                .isEqualTo(DomesticStockStatus.PARTIALLY_SOLD);
    }

    @Test
    void 요율은_개설후_1년까지_우대_이후_기본이고_거래세율은_규칙값이다() {
        LocalDate opened = LocalDate.of(2026, 3, 24);
        assertThat(
                        StockOrderCalculator.commissionRatePercent(
                                opened, LocalDate.of(2026, 11, 18), RULES))
                .isEqualByComparingTo("0.0040");
        assertThat(
                        StockOrderCalculator.commissionRatePercent(
                                opened, LocalDate.of(2027, 3, 24), RULES))
                .isEqualByComparingTo("0.0150");
        assertThat(StockOrderCalculator.securitiesTaxRatePercent(LocalDate.of(2026, 11, 18), RULES))
                .isEqualByComparingTo("0.2000");
    }

    @Test
    void 거래세_대상은_코스피_코스닥_주식뿐이다() {
        assertThat(StockOrderCalculator.isTaxable(Type.STOCK, "KOSPI")).isTrue();
        assertThat(StockOrderCalculator.isTaxable(Type.STOCK, " kosdaq ")).isTrue();
        assertThat(StockOrderCalculator.isTaxable(Type.FUND, "KOSPI")).isFalse();
        assertThat(StockOrderCalculator.isTaxable(Type.STOCK, null)).isFalse();
    }
}
