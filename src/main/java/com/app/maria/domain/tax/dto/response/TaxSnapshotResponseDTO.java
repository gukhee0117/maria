package com.app.maria.domain.tax.dto.response;

import com.app.maria.domain.tax.dto.TaxSnapshotDTO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class TaxSnapshotResponseDTO {
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

    public static TaxSnapshotResponseDTO of(TaxSnapshotDTO dto) {
        return TaxSnapshotResponseDTO.builder()
                .accountId(dto.getAccountId())
                .calculatedAt(dto.getCalculatedAt())
                .weightedSell(dto.getWeightedSell())
                .originalGainAmount(dto.getOriginalGainAmount())
                .weightedGain(dto.getWeightedGain())
                .weightedExternalAmount(dto.getWeightedExternalAmount())
                .adjustRatio(dto.getAdjustRatio())
                .finalDeduction(dto.getFinalDeduction())
                .finalTax(dto.getFinalTax())
                .needsFinalReport(dto.isNeedsFinalReport())
                .needsClawback(dto.isNeedsClawback())
                .build();
    }
}
