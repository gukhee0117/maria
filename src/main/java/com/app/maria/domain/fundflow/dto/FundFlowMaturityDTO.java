package com.app.maria.domain.fundflow.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
public class FundFlowMaturityDTO {

    private LocalDate asOfDate;

    /** 가장 이른 만기월(1일). 대상 건이 없으면 null. */
    private LocalDate firstMaturityMonth;

    /** 아직 묶여 있는 잔액(1년 미경과 건의 남은 원금) */
    private BigDecimal lockedAmount;

    private Long lockedAccounts;

    /** 1년이 지났지만 아직 남아 있는 잔액 */
    private BigDecimal maturedRemainingAmount;

    /** 해지 계좌라서 months 에서 뺀 확정 원금 합 */
    private BigDecimal excludedClosedPaidAmount;

    private List<FundFlowMaturityMonthDTO> months;
}
