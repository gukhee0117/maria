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
public class FundFlowLorenzPointDTO {

    /** 하위 몇 %의 계좌인지 (0~1) */
    private BigDecimal populationShare;

    /** 그 계좌들이 가진 감면액 비율 (0~1) */
    private BigDecimal valueShare;
}
