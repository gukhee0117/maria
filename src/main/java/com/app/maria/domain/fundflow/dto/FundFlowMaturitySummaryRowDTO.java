package com.app.maria.domain.fundflow.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** 묶임·만기 분류 요약의 매퍼 행. */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@Builder(toBuilder = true)
public class FundFlowMaturitySummaryRowDTO {

    /** 혜택이 취소되지 않은 계좌 중 아직 1년이 안 지난 건의 남은 원금 합 */
    private BigDecimal lockedAmount;

    /** 혜택이 취소되지 않았고 묶인 잔액이 남아 있는 계좌 수 */
    private Long lockedAccounts;

    /** 혜택이 취소되지 않은 계좌 중 1년이 지났지만 아직 남아 있는 원금 합 */
    private BigDecimal maturedRemainingAmount;

    /** 혜택이 취소된 계좌의 남은 원금 합(묶임·만기와 상관없이 전부) */
    private BigDecimal canceledRemainingAmount;

    /** 남은 원금이 있는 혜택 취소 계좌 수 */
    private Long canceledAccounts;

    /** left_amount 가 없는 대상 환전 건 수(정상이면 0) */
    private Long lotsWithoutLeftAmount;
}
