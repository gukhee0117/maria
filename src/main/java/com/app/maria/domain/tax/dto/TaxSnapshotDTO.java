package com.app.maria.domain.tax.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TaxSnapshotDTO {
    private Long snapshotId;
    private Long accountId;
    private LocalDateTime calculatedAt;
    private BigDecimal weightedSell;
    private BigDecimal originalGainAmount;
    private BigDecimal weightedGain;
    private BigDecimal weightedExternalAmount;
    private BigDecimal adjustRatio;
    private BigDecimal finalDeduction;
    private BigDecimal finalTax;
    private boolean needsFinalReport;
    private boolean needsClawback;

    public static TaxSnapshotDTO of(
            Long accountId, LocalDateTime calculatedAt, TaxCalculationResultDTO result) {
        return TaxSnapshotDTO.builder()
                .accountId(accountId)
                .calculatedAt(calculatedAt)
                .weightedSell(result.getWeightedSell())
                .originalGainAmount(result.getOriginalGainAmount())
                .weightedGain(result.getWeightedGain())
                .weightedExternalAmount(result.getWeightedExternalAmount())
                .adjustRatio(result.getAdjustRatio())
                .finalDeduction(result.getFinalDeduction())
                .finalTax(result.getFinalTax())
                .build();
    }
}
