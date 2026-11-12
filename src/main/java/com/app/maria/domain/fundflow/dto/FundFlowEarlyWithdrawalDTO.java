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
public class FundFlowEarlyWithdrawalDTO {

    private LocalDate asOfDate;
    private Long finalizedAccounts;
    private BigDecimal finalizedAmount;
    private Long earlyAccounts;
    private BigDecimal earlyWithdrawnAmount;

    /** 분모가 0이면 null */
    private BigDecimal accountShare;

    /** 분모가 0이면 null */
    private BigDecimal amountShare;

    private List<FundFlowEarlyWithdrawalCauseDTO> byCause;
}
