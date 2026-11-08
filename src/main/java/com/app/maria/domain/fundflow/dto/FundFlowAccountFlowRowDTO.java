package com.app.maria.domain.fundflow.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** 계좌 한 개의 기간 내 확정 유입과 국내 매수 체결금액. 연령대 중앙값 계산용 원천 행. */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@Builder(toBuilder = true)
public class FundFlowAccountFlowRowDTO {

    private String ageBand;
    private BigDecimal inflow;
    private BigDecimal buyExec;
}
