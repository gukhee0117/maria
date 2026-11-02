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
public class FundFlowMonthDTO {
    private LocalDate flowMonth;
    private BigDecimal foreignSellAmount;
    private BigDecimal krwInflowAmount;
    private BigDecimal domesticBuyAmount;
    private BigDecimal domesticSellAmount;
    private BigDecimal withdrawalAmount;
    private BigDecimal cashBalanceBegin;
    private BigDecimal cashBalanceEnd;
    private boolean partial;
}
