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
public class FundFlowOverviewDTO {
    private LocalDate asOfDate;
    private Long openedAccounts;
    private BigDecimal inflowTotal;
    private BigDecimal domesticBuyTotal;
    private BigDecimal domesticSellTotal;
    private BigDecimal reinvestRatio;
    private BigDecimal netInvestRatio;
}
