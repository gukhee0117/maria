package com.app.maria.domain.tax.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaxBreakdownDTO {
    private List<TaxPeriodBreakdownDTO> periodBreakdown;
    private List<TaxLotDetailDTO> sellLotDetails;
    private List<TaxExternalTradeDetailDTO> externalTradeDetails;

    public static TaxBreakdownDTO of(
            List<TaxPeriodBreakdownDTO> periodBreakdown,
            List<TaxLotDetailDTO> sellLotDetails,
            List<TaxExternalTradeDetailDTO> externalTradeDetails) {
        return TaxBreakdownDTO.builder()
                .periodBreakdown(periodBreakdown)
                .sellLotDetails(sellLotDetails)
                .externalTradeDetails(externalTradeDetails)
                .build();
    }
}
