package com.app.maria.domain.fundflow.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@Builder(toBuilder = true)
public class FundFlowReliefDTO {
    private LocalDate asOfDate;
    private String scope;
    private Long accounts;
    private BigDecimal generalTaxTotal;
    private BigDecimal finalTaxTotal;
    private BigDecimal reliefTotal;
    private Long accountsWithRelief;
    private BigDecimal netInvestTotal;
    private BigDecimal investPerRelief;
}
