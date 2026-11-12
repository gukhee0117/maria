package com.app.maria.domain.fundflow.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** 원인별 1년 미경과 원금 인출. 매퍼 행과 VO 로 함께 쓴다. */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@Builder(toBuilder = true)
public class FundFlowEarlyWithdrawalCauseDTO {

    /** EarlyWithdrawalCause 이름 */
    private String cause;

    private String label;
    private Long accounts;
    private BigDecimal amount;
}
