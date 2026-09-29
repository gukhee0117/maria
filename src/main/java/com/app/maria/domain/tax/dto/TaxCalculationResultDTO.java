package com.app.maria.domain.tax.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TaxCalculationResultDTO {
    private BigDecimal originalGainAmount;
    private BigDecimal weightedGain;
    private BigDecimal weightedSell;
    private BigDecimal weightedExternalAmount;
    private BigDecimal adjustRatio;
    private BigDecimal finalDeduction;
    private BigDecimal finalTax;

    public static TaxCalculationResultDTO of(
            RiaSellAggregateDTO riaSell,
            BigDecimal weightedExternalAmount,
            BigDecimal adjustRatio,
            BigDecimal finalDeduction,
            BigDecimal finalTax) {
        return TaxCalculationResultDTO.builder()
                .originalGainAmount(riaSell.getOriginalGainAmount())
                .weightedGain(riaSell.getWeightedGain())
                .weightedSell(riaSell.getWeightedSell())
                .weightedExternalAmount(weightedExternalAmount)
                .adjustRatio(adjustRatio)
                .finalDeduction(finalDeduction)
                .finalTax(finalTax)
                .build();
    }
}
