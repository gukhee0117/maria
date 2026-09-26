package com.app.maria.domain.tax.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class TaxPeriodBreakdownDTO {
    private LocalDate validFrom;
    private LocalDate validTo;
    private BigDecimal weight;
    private BigDecimal sellAmount;
    private BigDecimal gainAmount;
    private BigDecimal externalNetBuyAmount;
}
