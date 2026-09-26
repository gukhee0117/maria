package com.app.maria.domain.tax.dto.response;

import com.app.maria.domain.tax.dto.TaxCalculationDTO;
import com.app.maria.domain.tax.dto.TaxCalculationResultDTO;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TaxCalculationPreviewResponseDTO {
    private Long accountId;
    private TaxCalculationResultDTO taxCalculationResultDTO;
    private String benefitChangeReason;
    private LocalDateTime benefitChangedAt;
    private TaxCalculationDTO latestSavedCalculation;

    public static TaxCalculationPreviewResponseDTO of(
            Long accountId,
            TaxCalculationResultDTO taxCalculationResultDTO,
            String benefitChangeReason,
            LocalDateTime benefitChangedAt,
            TaxCalculationDTO latestSavedCalculation) {
        return TaxCalculationPreviewResponseDTO.builder()
                .accountId(accountId)
                .taxCalculationResultDTO(taxCalculationResultDTO)
                .benefitChangeReason(benefitChangeReason)
                .benefitChangedAt(benefitChangedAt)
                .latestSavedCalculation(latestSavedCalculation)
                .build();
    }
}
