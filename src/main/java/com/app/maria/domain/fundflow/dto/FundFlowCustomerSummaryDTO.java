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
public class FundFlowCustomerSummaryDTO {
    private LocalDate asOfDate;
    private boolean hasAccount;
    private BigDecimal soldForeignAmount;
    private BigDecimal krwInflowAmount;
    private BigDecimal domesticBuyAmount;

    /** 국내 매수 수수료 합계(매수 원가에는 포함하지 않고, 예수금 계산식에서 따로 뺀다) */
    private BigDecimal domesticBuyFeeAmount;

    private BigDecimal cashBalance;
    private BigDecimal reinvestRatio;
}
