package com.app.maria.domain.tax.dto.response;

import com.app.maria.domain.tax.dto.TaxCalculationResultDTO;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TaxExpectedReliefResponseDTO {
    private Long accountId;
    private LocalDate expectedFinalAt;
    private TaxCalculationResultDTO taxCalculationResultDTO;

    public static TaxExpectedReliefResponseDTO of(
            Long accountId, LocalDate expectedFinalAt, TaxCalculationResultDTO result) {
        return TaxExpectedReliefResponseDTO.builder()
                .accountId(accountId)
                .expectedFinalAt(expectedFinalAt)
                .taxCalculationResultDTO(result)
                .build();
    }
}
