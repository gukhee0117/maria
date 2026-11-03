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
@Builder
public class FundFlowFunnelStageDTO {
    private Integer stageNo;
    private String stage;
    private Long customers;
    private BigDecimal convPrev;
    private BigDecimal convTotal;
}
