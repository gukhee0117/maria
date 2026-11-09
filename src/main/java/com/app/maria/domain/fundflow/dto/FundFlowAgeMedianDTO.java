package com.app.maria.domain.fundflow.dto;

import java.math.BigDecimal;
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
public class FundFlowAgeMedianDTO {

    /** null이면 전체 */
    private String ageBand;

    private Long accounts;
    private Long accountsWithInflow;

    /** 유입이 있는 계좌의 확정 유입 중앙값. 5명 미만이면 null */
    private BigDecimal inflowMedian;

    /** 유입이 있는 계좌의 (국내 매수 ÷ 확정 유입) 중앙값. 5명 미만이면 null */
    private BigDecimal reinvestMedian;
}
