package com.app.maria.domain.tax.service;

import com.app.maria.domain.tax.dto.ExternalBuyDTO;
import com.app.maria.domain.tax.dto.RiaSellAggregateDTO;
import com.app.maria.domain.tax.dto.SellLotDTO;
import com.app.maria.domain.tax.dto.TaxCalculationResultDTO;
import com.app.maria.domain.tax.dto.TaxExternalTradeDetailDTO;
import com.app.maria.domain.tax.dto.TaxLotDetailDTO;
import com.app.maria.domain.tax.dto.TaxPeriodBreakdownDTO;
import com.app.maria.domain.tax.dto.TaxRuleDTO;
import com.app.maria.domain.tax.type.TaxRuleType;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TaxCalculator {

    private static final int RATIO_SCALE = 4;
    private static final int AMOUNT_SCALE = 2;
    private static final int DIVIDE_SCALE = 12;

    public TaxCalculationResultDTO calculate(
            List<SellLotDTO> sellLots,
            List<TaxRuleDTO> taxRules,
            List<ExternalBuyDTO> externalTrades,
            boolean reliefExcluded) {
        RiaSellAggregateDTO riaSell = aggregateRiaSell(sellLots, taxRules);

        BigDecimal weightedExternalAmount = aggregateExternal(externalTrades, taxRules);
        BigDecimal adjustRatio =
                reliefExcluded
                        ? BigDecimal.ZERO.setScale(RATIO_SCALE, RoundingMode.HALF_UP)
                        : adjustRatio(weightedExternalAmount, riaSell.getWeightedSell());
        BigDecimal finalDeduction = findDeduction(riaSell.getWeightedGain(), adjustRatio);
        BigDecimal finalTax = finalTax(riaSell.getOriginalGainAmount(), finalDeduction, taxRules);
        List<TaxPeriodBreakdownDTO> periodBreakdown =
                buildPeriodBreakdown(sellLots, externalTrades, taxRules);

        return TaxCalculationResultDTO.of(
                riaSell,
                weightedExternalAmount,
                adjustRatio,
                finalDeduction,
                finalTax,
                periodBreakdown,
                buildLotDetails(sellLots),
                buildExternalTradeDetails(externalTrades));
    }

    private List<TaxLotDetailDTO> buildLotDetails(List<SellLotDTO> sellLots) {
        List<TaxLotDetailDTO> details = new ArrayList<>();
        for (SellLotDTO lot : sellLots) {
            details.add(
                    TaxLotDetailDTO.builder()
                            .productLabel(lot.getProductLabel())
                            .finalAt(lot.getFinalAt())
                            .sellAmount(
                                    lot.getFinalAmount()
                                            .setScale(AMOUNT_SCALE, RoundingMode.HALF_UP))
                            .gainAmount(
                                    lot.getFinalAmount()
                                            .subtract(purchaseCost(lot))
                                            .setScale(AMOUNT_SCALE, RoundingMode.HALF_UP))
                            .build());
        }
        details.sort((a, b) -> b.getFinalAt().compareTo(a.getFinalAt()));
        return details;
    }

    private List<TaxExternalTradeDetailDTO> buildExternalTradeDetails(
            List<ExternalBuyDTO> externalTrades) {
        List<TaxExternalTradeDetailDTO> details = new ArrayList<>();
        for (ExternalBuyDTO trade : externalTrades) {
            details.add(
                    TaxExternalTradeDetailDTO.builder()
                            .productLabel(trade.getProductLabel())
                            .tradeDate(trade.getTradeDate())
                            .netBuyAmount(
                                    trade.getNetBuyAmount()
                                            .setScale(AMOUNT_SCALE, RoundingMode.HALF_UP))
                            .build());
        }
        details.sort((a, b) -> b.getTradeDate().compareTo(a.getTradeDate()));
        return details;
    }

    private List<TaxPeriodBreakdownDTO> buildPeriodBreakdown(
            List<SellLotDTO> sellLots,
            List<ExternalBuyDTO> externalTrades,
            List<TaxRuleDTO> taxRules) {
        List<TaxPeriodBreakdownDTO> breakdown = new ArrayList<>();
        for (TaxRuleDTO rule : taxRules) {
            if (rule.getRuleType() != TaxRuleType.RELIEF_RATE) {
                continue;
            }
            BigDecimal weight =
                    rule.getRuleValue()
                            .divide(BigDecimal.valueOf(100), RATIO_SCALE, RoundingMode.HALF_UP);
            BigDecimal sellAmount = BigDecimal.ZERO;
            BigDecimal gainAmount = BigDecimal.ZERO;
            for (SellLotDTO lot : sellLots) {
                if (!inRange(lot.getFinalAt(), rule)) {
                    continue;
                }
                sellAmount = sellAmount.add(lot.getFinalAmount());
                gainAmount = gainAmount.add(lot.getFinalAmount().subtract(purchaseCost(lot)));
            }
            BigDecimal externalNetBuyAmount = BigDecimal.ZERO;
            for (ExternalBuyDTO externalTrade : externalTrades) {
                if (!inRange(externalTrade.getTradeDate(), rule)) {
                    continue;
                }
                externalNetBuyAmount = externalNetBuyAmount.add(externalTrade.getNetBuyAmount());
            }
            breakdown.add(
                    TaxPeriodBreakdownDTO.builder()
                            .validFrom(rule.getValidFrom())
                            .validTo(rule.getValidTo())
                            .weight(weight)
                            .sellAmount(sellAmount.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP))
                            .gainAmount(gainAmount.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP))
                            .externalNetBuyAmount(
                                    externalNetBuyAmount.setScale(
                                            AMOUNT_SCALE, RoundingMode.HALF_UP))
                            .build());
        }
        breakdown.sort((a, b) -> a.getValidFrom().compareTo(b.getValidFrom()));
        return breakdown;
    }

    private BigDecimal purchaseCost(SellLotDTO lot) {
        return lot.getPurchasePrice().multiply(lot.getPurchaseFxRate()).multiply(lot.getSellQty());
    }

    private RiaSellAggregateDTO aggregateRiaSell(List<SellLotDTO> lots, List<TaxRuleDTO> taxRules) {
        BigDecimal weightedSell = BigDecimal.ZERO;
        BigDecimal weightedGain = BigDecimal.ZERO;
        BigDecimal originalGain = BigDecimal.ZERO;

        for (SellLotDTO lot : lots) {
            BigDecimal weight = findWeight(taxRules, lot.getFinalAt());

            BigDecimal sellAmount = lot.getFinalAmount();
            BigDecimal gainAmount = sellAmount.subtract(purchaseCost(lot));

            weightedSell = weightedSell.add(sellAmount.multiply(weight));
            weightedGain = weightedGain.add(gainAmount.multiply(weight));
            originalGain = originalGain.add(gainAmount);
        }

        return RiaSellAggregateDTO.of(weightedSell, weightedGain, originalGain);
    }

    private BigDecimal aggregateExternal(
            List<ExternalBuyDTO> externalTrades, List<TaxRuleDTO> taxRules) {
        BigDecimal sum = BigDecimal.ZERO;
        for (ExternalBuyDTO externalTrade : externalTrades) {
            BigDecimal weight = findWeight(taxRules, externalTrade.getTradeDate());
            sum = sum.add(externalTrade.getNetBuyAmount().multiply(weight));
        }
        return sum.max(BigDecimal.ZERO).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal adjustRatio(BigDecimal weightedExternalAmount, BigDecimal weightedSell) {
        if (weightedSell.signum() <= 0) {
            return BigDecimal.ZERO.setScale(RATIO_SCALE, RoundingMode.HALF_UP);
        }

        return BigDecimal.ONE
                .subtract(
                        weightedExternalAmount.divide(
                                weightedSell, DIVIDE_SCALE, RoundingMode.HALF_UP))
                .max(BigDecimal.ZERO)
                .min(BigDecimal.ONE)
                .setScale(RATIO_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal finalTax(
            BigDecimal originalGain, BigDecimal finalDeduction, List<TaxRuleDTO> taxRules) {
        BigDecimal taxBase =
                originalGain
                        .subtract(findConstantRule(taxRules, TaxRuleType.BASIC_DEDUCTION))
                        .subtract(finalDeduction);
        if (taxBase.signum() <= 0) {
            return BigDecimal.ZERO.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        }
        return taxBase.multiply(findConstantRule(taxRules, TaxRuleType.TAX_RATE))
                .setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal findDeduction(BigDecimal weightedGain, BigDecimal adjustRatio) {
        if (weightedGain.signum() <= 0) {
            return BigDecimal.ZERO.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        }
        return weightedGain.multiply(adjustRatio).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal findWeight(List<TaxRuleDTO> taxRules, LocalDate finalAt) {
        return findRuleValue(taxRules, TaxRuleType.RELIEF_RATE, finalAt)
                .divide(BigDecimal.valueOf(100), RATIO_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal findRuleValue(
            List<TaxRuleDTO> taxRules, TaxRuleType ruleType, LocalDate baseDate) {
        return taxRules.stream()
                .filter(rule -> ruleType == rule.getRuleType())
                .filter(
                        rule ->
                                !baseDate.isBefore(rule.getValidFrom())
                                        && !baseDate.isAfter(rule.getValidTo()))
                .findFirst()
                .map(TaxRuleDTO::getRuleValue)
                // 해당 날짜/타입을 커버하는 tax_rule 행이 없음 (규칙 공백 구간) → 배치에서 skip 처리됨
                .orElseThrow(
                        () ->
                                new AppException(
                                        ErrorType.TAX_RULE_NOT_FOUND,
                                        "baseDate=" + baseDate + ", ruleType=" + ruleType));
    }

    private boolean inRange(LocalDate date, TaxRuleDTO rule) {
        return !date.isBefore(rule.getValidFrom()) && !date.isAfter(rule.getValidTo());
    }

    private BigDecimal findConstantRule(List<TaxRuleDTO> taxRules, TaxRuleType ruleType) {
        return taxRules.stream()
                .filter(rule -> ruleType == rule.getRuleType())
                .findFirst()
                .map(TaxRuleDTO::getRuleValue)
                // BASIC_DEDUCTION/TAX_RATE처럼 valid_from~valid_to 없이 항상 존재해야 하는 상수 규칙이 누락된 경우
                .orElseThrow(() -> new AppException(ErrorType.TAX_RULE_NOT_FOUND, ruleType));
    }
}
