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

    /** 가정 매도 대상인 잔량이 있는 입고 lot 개수. */
    private int holdingCount;

    public static TaxExpectedReliefResponseDTO of(
            Long accountId,
            LocalDate expectedFinalAt,
            TaxCalculationResultDTO result,
            int holdingCount) {
        return TaxExpectedReliefResponseDTO.builder()
                .accountId(accountId)
                .expectedFinalAt(expectedFinalAt)
                .taxCalculationResultDTO(result)
                .holdingCount(holdingCount)
                .build();
    }
}
