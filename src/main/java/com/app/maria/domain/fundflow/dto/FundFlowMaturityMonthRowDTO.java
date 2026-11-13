package com.app.maria.domain.fundflow.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** 만기월별 집계의 매퍼 행. 만기월은 연·월 정수로 받고 서비스에서 날짜로 만든다. */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@Builder(toBuilder = true)
public class FundFlowMaturityMonthRowDTO {

    /** 만기 연도 = 환전 확정 연도 + 1 */
    private Integer maturityYear;

    /** 만기 월 = 환전 확정 월 */
    private Integer maturityMonth;

    private Long lots;
    private Long accounts;
    private BigDecimal paidAmount;

    /** 혜택이 취소되지 않은 계좌의 남은 원금 합 */
    private BigDecimal remainingAmount;

    /** 혜택이 취소된(benefit = IMPOSSIBLE) 계좌의 남은 원금 합 */
    private BigDecimal canceledRemainingAmount;
}
