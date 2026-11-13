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
public class FundFlowMaturityMonthDTO {

    /** 만기월의 1일 */
    private LocalDate maturityMonth;

    /** 그 달에 만기가 오는 환전 건 수(전량 인출된 건 포함) */
    private Long lots;

    private Long accounts;

    /** 확정 원금 합(krw_exchange.final_amount) */
    private BigDecimal paidAmount;

    /** 혜택이 취소되지 않은 계좌의 현재 남은 원금 합(left_amount.cur_amount) */
    private BigDecimal remainingAmount;

    /** 혜택이 취소된 계좌의 현재 남은 원금 합 */
    private BigDecimal canceledRemainingAmount;
}
