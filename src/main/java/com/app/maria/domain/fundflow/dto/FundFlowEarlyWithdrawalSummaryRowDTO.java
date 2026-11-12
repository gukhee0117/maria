package com.app.maria.domain.fundflow.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** 1년 미경과 원금 인출 비율의 분모·분자 합계(매퍼 행). */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@Builder(toBuilder = true)
public class FundFlowEarlyWithdrawalSummaryRowDTO {

    /** 분모: FINALIZED 환전이 있는 계좌 수(해지 계좌 포함) */
    private Long finalizedAccounts;

    /** 분모: FINALIZED 환전의 확정 원금 합 */
    private BigDecimal finalizedAmount;

    /** 분자: 1년 미경과 원금이 포함된 완료 인출이 있는 계좌 수 */
    private Long earlyAccounts;

    /** 분자: 그 인출에서 1년 미경과 원금으로 배분된 금액 합 */
    private BigDecimal earlyWithdrawnAmount;
}
