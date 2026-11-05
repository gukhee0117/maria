package com.app.maria.domain.fundflow.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** 납입건(확정된 원화 환전 1건) 하나. 같은 납입일끼리는 서비스에서 합친다. */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@Builder(toBuilder = true)
public class FundFlowWithdrawalLotRowDTO {
    private Long exchangeId;

    /** 납입일(원화로 환전되어 대금결제된 날). 1년 인출 시계의 기준점. */
    private LocalDateTime finalAt;

    /** 처음 납입액(krw_exchange.final_amount). */
    private BigDecimal paidAmount;

    /** 인출 후 남은 원금(left_amount.cur_amount). 전량 인출이면 0. */
    private BigDecimal remainingAmount;

    /** 이 납입건에서 FIFO로 배분된 인출액 합계(withdrawal_allocation). */
    private BigDecimal withdrawnAmount;
}
