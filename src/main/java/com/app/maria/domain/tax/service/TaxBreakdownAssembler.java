package com.app.maria.domain.tax.service;

import com.app.maria.domain.tax.dto.ExternalBuyDTO;
import com.app.maria.domain.tax.dto.SellLotDTO;
import com.app.maria.domain.tax.dto.TaxBreakdownDTO;
import com.app.maria.domain.tax.dto.TaxExternalTradeDetailDTO;
import com.app.maria.domain.tax.dto.TaxLotDetailDTO;
import com.app.maria.domain.tax.dto.TaxPeriodBreakdownDTO;
import com.app.maria.domain.tax.dto.TaxRuleDTO;
import com.app.maria.domain.tax.type.TaxRuleType;
import com.app.maria.domain.tax.type.TaxScale;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TaxBreakdownAssembler {

    public TaxBreakdownDTO assemble(
            List<SellLotDTO> sellLots,
            List<ExternalBuyDTO> externalTrades,
            List<TaxRuleDTO> taxRules) {
        return TaxBreakdownDTO.of(
                buildPeriodBreakdown(sellLots, externalTrades, taxRules),
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
                            .sellAmount(TaxScale.AMOUNT.round(lot.getFinalAmount()))
                            .gainAmount(TaxScale.AMOUNT.round(TaxCalculations.gainAmount(lot)))
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
                            .netBuyAmount(TaxScale.AMOUNT.round(trade.getNetBuyAmount()))
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
            BigDecimal weight = TaxCalculations.weightOf(rule.getRuleValue());
            BigDecimal sellAmount = BigDecimal.ZERO;
            BigDecimal gainAmount = BigDecimal.ZERO;
            for (SellLotDTO lot : sellLots) {
                if (!inRange(lot.getFinalAt(), rule)) {
                    continue;
                }
                sellAmount = sellAmount.add(lot.getFinalAmount());
                gainAmount = gainAmount.add(TaxCalculations.gainAmount(lot));
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
                            .sellAmount(TaxScale.AMOUNT.round(sellAmount))
                            .gainAmount(TaxScale.AMOUNT.round(gainAmount))
                            .externalNetBuyAmount(TaxScale.AMOUNT.round(externalNetBuyAmount))
                            .build());
        }
        breakdown.sort((a, b) -> a.getValidFrom().compareTo(b.getValidFrom()));
        return breakdown;
    }

    private boolean inRange(LocalDate date, TaxRuleDTO rule) {
        return !date.isBefore(rule.getValidFrom()) && !date.isAfter(rule.getValidTo());
    }
}
