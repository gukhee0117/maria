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

    /** 아직 1년이 안 지난 건의 남은 원금 합 */
    private BigDecimal lockedAmount;

    /** 묶인 잔액이 남아 있는 계좌 수 */
    private Long lockedAccounts;

    /** 1년이 지났지만 아직 남아 있는 원금 합 */
    private BigDecimal maturedRemainingAmount;
}
