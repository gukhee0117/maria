package com.app.maria.domain.fundflow.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** 기간 하나의 말일 기준 잔액. */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@Builder(toBuilder = true)
public class FundFlowTrendPointDTO {
    private LocalDate periodStart;

    /** 기간 말일. 마지막 기간은 업무일 기준일(asOfDate)까지만. */
    private LocalDate periodEnd;

    /** 기간 말 국내 보유 매입금액(보유수량 × 이동평균 매입가, 수수료 제외). */
    private BigDecimal invested;

    /** 기간 말 예수금. */
    private BigDecimal cash;

    /** 이 기간 안에 자금 이동(원화 확정·매매·인출)이 있었는지. */
    private boolean hasTransactions;
}
