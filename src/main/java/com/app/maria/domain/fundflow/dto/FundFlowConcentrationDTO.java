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
public class FundFlowConcentrationDTO {

    private LocalDate asOfDate;

    /** 감면액이 0보다 큰 계좌 수 */
    private Long accounts;

    private BigDecimal reliefTotal;

    /** 감면액 상위 10% 계좌의 점유율. 계좌가 없으면 null */
    private BigDecimal top10Share;

    private BigDecimal gini;
    private List<FundFlowLorenzPointDTO> lorenz;
}
