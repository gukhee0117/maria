package com.app.maria.domain.fundflow.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** 납입일 하나의 합계. */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@Builder(toBuilder = true)
public class FundFlowWithdrawalLotDTO {
    private LocalDate paidDate;

    /** 인출 가능일 = 납입일 + 1년. */
    private LocalDate maturityDate;

    private BigDecimal paidAmount;
    private BigDecimal withdrawnAmount;
    private BigDecimal remainingAmount;

    /** 업무일 기준으로 1년이 지났는지(인출 처리와 같은 판정). */
    private boolean matured;
}
