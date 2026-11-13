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

    /** 아직 묶여 있는 잔액(혜택이 취소되지 않은 계좌의 1년 미경과 건의 남은 원금) */
    private BigDecimal lockedAmount;

    private Long lockedAccounts;

    /** 1년이 지났지만 아직 남아 있는 잔액(혜택이 취소되지 않은 계좌) */
    private BigDecimal maturedRemainingAmount;

    /** 혜택이 취소된 계좌의 남은 원금 합. 1년 유지 조건과 무관해 따로 집계한다 */
    private BigDecimal canceledRemainingAmount;

    /** 남은 원금이 있는 혜택 취소 계좌 수 */
    private Long canceledAccounts;

    /** left_amount 가 없는 대상 환전 건 수. 정상이면 0 */
    private Long lotsWithoutLeftAmount;

    /** 해지 계좌라서 months 에서 뺀 확정 원금 합 */
    private BigDecimal excludedClosedPaidAmount;

    private List<FundFlowMaturityMonthDTO> months;
}
