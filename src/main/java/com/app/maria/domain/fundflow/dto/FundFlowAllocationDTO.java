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
public class FundFlowAllocationDTO {
    private LocalDate asOfDate;
    private BigDecimal totalCost;
    private List<FundFlowBucketDTO> buckets;
    private BigDecimal hhiBucket;
    private BigDecimal hhiProduct;
    private List<FundFlowNetBuyDTO> topNetBuy;
}
