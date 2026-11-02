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
public class FundFlowSegmentRowDTO {
    private String ageBand;
    private String investorType;
    private Long accounts;
    private Long accountsWithInflow;
    private BigDecimal inflowTotal;
    private BigDecimal buyTotal;
    private BigDecimal sellTotal;
}
