package com.app.maria.domain.tax.service;

import com.app.maria.domain.tax.dto.ExternalBuyDTO;
import com.app.maria.domain.tax.dto.RiaSellAggregateDTO;
import com.app.maria.domain.tax.dto.SellLotDTO;
import com.app.maria.domain.tax.dto.TaxCalculationResultDTO;
import com.app.maria.domain.tax.dto.TaxRuleDTO;
import com.app.maria.domain.tax.type.TaxRuleType;
import com.app.maria.domain.tax.type.TaxScale;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TaxCalculator {

    public TaxCalculationResultDTO calculate(
            List<SellLotDTO> sellLots,
            List<TaxRuleDTO> taxRules,
            List<ExternalBuyDTO> externalTrades,
            boolean reliefExcluded) {
        RiaSellAggregateDTO riaSell = aggregateRiaSell(sellLots, taxRules);

        BigDecimal weightedExternalAmount = aggregateExternalNetBuy(externalTrades, taxRules);
        BigDecimal ratio =
                reliefExcluded
                        ? TaxScale.RATIO.round(BigDecimal.ZERO)
                        : adjustRatio(weightedExternalAmount, riaSell.getWeightedSell());
        BigDecimal deduction = finalDeduction(riaSell.getWeightedGain(), ratio);
        BigDecimal tax = finalTax(riaSell.getOriginalGainAmount(), deduction, taxRules);

        return TaxCalculationResultDTO.of(riaSell, weightedExternalAmount, ratio, deduction, tax);
    }

    private RiaSellAggregateDTO aggregateRiaSell(List<SellLotDTO> lots, List<TaxRuleDTO> taxRules) {
        BigDecimal weightedSell = BigDecimal.ZERO;
        BigDecimal weightedGain = BigDecimal.ZERO;
        BigDecimal originalGain = BigDecimal.ZERO;

        for (SellLotDTO lot : lots) {
            BigDecimal weight = findWeight(taxRules, lot.getFinalAt());

            BigDecimal sellAmount = lot.getFinalAmount();
            BigDecimal gainAmount = TaxCalculations.gainAmount(lot);

            weightedSell = weightedSell.add(sellAmount.multiply(weight));
            weightedGain = weightedGain.add(gainAmount.multiply(weight));
            originalGain = originalGain.add(gainAmount);
        }

        return RiaSellAggregateDTO.of(weightedSell, weightedGain, originalGain);
    }

    private BigDecimal aggregateExternalNetBuy(
            List<ExternalBuyDTO> externalTrades, List<TaxRuleDTO> taxRules) {
        BigDecimal sum = BigDecimal.ZERO;
        for (ExternalBuyDTO externalTrade : externalTrades) {
            BigDecimal weight = findWeight(taxRules, externalTrade.getTradeDate());
            sum = sum.add(externalTrade.getNetBuyAmount().multiply(weight));
        }
        return TaxScale.AMOUNT.round(sum.max(BigDecimal.ZERO));
    }

    private BigDecimal adjustRatio(BigDecimal weightedExternalAmount, BigDecimal weightedSell) {
        if (weightedSell.signum() <= 0) {
            return TaxScale.RATIO.round(BigDecimal.ZERO);
        }

        BigDecimal ratio =
                BigDecimal.ONE
                        .subtract(
                                weightedExternalAmount.divide(
                                        weightedSell,
                                        TaxScale.DIVIDE.scale(),
                                        RoundingMode.HALF_UP))
                        .max(BigDecimal.ZERO)
                        .min(BigDecimal.ONE);
        return TaxScale.RATIO.round(ratio);
    }

    private BigDecimal finalTax(
            BigDecimal originalGain, BigDecimal finalDeduction, List<TaxRuleDTO> taxRules) {
        BigDecimal taxBase =
                originalGain
                        .subtract(findConstantRuleValue(taxRules, TaxRuleType.BASIC_DEDUCTION))
                        .subtract(finalDeduction);
        if (taxBase.signum() <= 0) {
            return TaxScale.AMOUNT.round(BigDecimal.ZERO);
        }
        return TaxScale.AMOUNT.round(
                taxBase.multiply(findConstantRuleValue(taxRules, TaxRuleType.TAX_RATE)));
    }

    private BigDecimal finalDeduction(BigDecimal weightedGain, BigDecimal ratio) {
        if (weightedGain.signum() <= 0) {
            return TaxScale.AMOUNT.round(BigDecimal.ZERO);
        }
        return TaxScale.AMOUNT.round(weightedGain.multiply(ratio));
    }

    private BigDecimal findWeight(List<TaxRuleDTO> taxRules, LocalDate finalAt) {
        return TaxCalculations.weightOf(findRuleValue(taxRules, TaxRuleType.RELIEF_RATE, finalAt));
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

    private BigDecimal findConstantRuleValue(List<TaxRuleDTO> taxRules, TaxRuleType ruleType) {
        return taxRules.stream()
                .filter(rule -> ruleType == rule.getRuleType())
                .findFirst()
                .map(TaxRuleDTO::getRuleValue)
                .orElseThrow(() -> new AppException(ErrorType.TAX_RULE_NOT_FOUND, ruleType));
    }
}
