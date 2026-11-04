package com.app.maria.domain.stockpackage.service;

import com.app.maria.domain.domestic.type.DomesticOrderSide;
import com.app.maria.domain.domestic.type.DomesticStockStatus;
import com.app.maria.domain.domestic.type.Type;
import com.app.maria.domain.tax.dto.TaxRuleDTO;
import com.app.maria.domain.tax.type.TaxRuleType;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 국내 주문의 돈 계산(체결금액·수수료·증권거래세·예수금 변동·평균매입가). 요율은 코드 상수가 아니라 tax_rule에서 체결일 기준으로 읽는다. 금액은 원 단위이고
 * 수수료·거래세는 원 미만을 버린다.
 */
final class StockOrderCalculator {
    private static final BigDecimal PERCENT = BigDecimal.valueOf(100);
    private static final Set<String> TAXED_MARKETS = Set.of("KOSPI", "KOSDAQ");

    private StockOrderCalculator() {}

    /** 체결금액 = 수량 × 체결가, 원 미만 반올림. 수수료·거래세는 들어 있지 않다. */
    static BigDecimal cost(BigDecimal qty, BigDecimal price) {
        return qty.multiply(price).setScale(0, RoundingMode.HALF_UP);
    }

    /** 계좌 개설일부터 우대 기간(tax_rule의 COMMISSION_RIA_YEARS년) 안이면 우대율, 아니면 기본율(%). */
    static BigDecimal commissionRatePercent(
            LocalDate openedDate, LocalDate tradeDate, List<TaxRuleDTO> rules) {
        int years = ruleValue(rules, TaxRuleType.COMMISSION_RIA_YEARS, tradeDate).intValueExact();
        TaxRuleType type =
                tradeDate.isBefore(openedDate.plusYears(years))
                        ? TaxRuleType.COMMISSION_RIA
                        : TaxRuleType.COMMISSION_BASE;
        return ruleValue(rules, type, tradeDate);
    }

    static BigDecimal commission(
            BigDecimal cost, LocalDate openedDate, LocalDate tradeDate, List<TaxRuleDTO> rules) {
        return percentOf(cost, commissionRatePercent(openedDate, tradeDate, rules));
    }

    /** 증권거래세율(%). 거래세 대상 종목을 팔 때만 적용된다. */
    static BigDecimal securitiesTaxRatePercent(LocalDate tradeDate, List<TaxRuleDTO> rules) {
        return ruleValue(rules, TaxRuleType.SEC_TX_TAX_STOCK, tradeDate);
    }

    /** 증권거래세 대상 종목인지: 코스피·코스닥 상장 주식(STOCK). ETF 등 펀드는 아니다. */
    static boolean isTaxable(Type type, String market) {
        return type == Type.STOCK
                && market != null
                && TAXED_MARKETS.contains(market.trim().toUpperCase());
    }

    /** 증권거래세는 매도만, 코스피·코스닥 주식(STOCK)만 낸다. ETF 등 펀드와 매수는 0이다. */
    static BigDecimal securitiesTax(
            DomesticOrderSide side,
            Type type,
            String market,
            BigDecimal cost,
            LocalDate tradeDate,
            List<TaxRuleDTO> rules) {
        return side == DomesticOrderSide.SELL && isTaxable(type, market)
                ? percentOf(cost, securitiesTaxRatePercent(tradeDate, rules))
                : BigDecimal.ZERO;
    }

    /** 예수금 변동 순액. 매수는 −(체결금액+수수료), 매도는 +(체결금액−수수료−거래세). */
    static BigDecimal netAmount(
            DomesticOrderSide side, BigDecimal cost, BigDecimal fee, BigDecimal tax) {
        return side == DomesticOrderSide.BUY
                ? cost.add(fee).negate()
                : cost.subtract(fee).subtract(tax);
    }

    /** 매수 후 평균매입가. 체결가 기준 이동평균이고 수수료는 넣지 않는다. 보유가 없으면 체결가로 새로 시작한다. */
    static BigDecimal averageAfterBuy(
            BigDecimal heldQty, BigDecimal heldAvg, BigDecimal qty, BigDecimal price) {
        BigDecimal total = heldQty.add(qty);
        return heldQty.multiply(heldAvg)
                .add(qty.multiply(price))
                .divide(total, 4, RoundingMode.HALF_UP);
    }

    /** 매도 후 상태: 전량이면 SOLD_OUT, 남으면 PARTIALLY_SOLD. */
    static DomesticStockStatus statusAfterSell(BigDecimal remainingQty) {
        return remainingQty.signum() == 0
                ? DomesticStockStatus.SOLD_OUT
                : DomesticStockStatus.PARTIALLY_SOLD;
    }

    /** 거래가 막힌 보유 상태인지. */
    static boolean isBlocked(DomesticStockStatus status) {
        return status == DomesticStockStatus.TRADE_RESTRICTED
                || status == DomesticStockStatus.TRADE_SUSPENDED;
    }

    private static BigDecimal percentOf(BigDecimal cost, BigDecimal ratePercent) {
        return cost.multiply(ratePercent).divide(PERCENT, 0, RoundingMode.DOWN);
    }

    private static BigDecimal ruleValue(List<TaxRuleDTO> rules, TaxRuleType type, LocalDate day) {
        return rules.stream()
                .filter(r -> r.getRuleType() == type)
                .filter(r -> !day.isBefore(r.getValidFrom()) && !day.isAfter(r.getValidTo()))
                .findFirst()
                .map(TaxRuleDTO::getRuleValue)
                .orElseThrow(
                        () ->
                                new AppException(
                                        ErrorType.TAX_RULE_NOT_FOUND,
                                        "baseDate=" + day + ", ruleType=" + type));
    }
}
