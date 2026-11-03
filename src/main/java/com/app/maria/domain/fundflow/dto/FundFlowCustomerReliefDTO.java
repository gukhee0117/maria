package com.app.maria.domain.fundflow.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
public class FundFlowCustomerReliefDTO {
    private LocalDate asOfDate;
    private boolean hasAccount;
    private String benefit;
    private String statusCode;
    private String statusLabel;
    private BigDecimal weightedSell;
    private BigDecimal adjustRatio;
    private BigDecimal finalDeduction;
    private BigDecimal finalTax;
    private BigDecimal generalTax;
    private BigDecimal reliefAmount;
    private LocalDateTime calculatedAt;
}
