package com.app.maria.domain.risk.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class StockRiskGradeDTO {
    private Long domesticProductId;
    private String ticker;
    private BigDecimal var975;
    private Integer riskGrade;
    private BigDecimal volatility;
    private LocalDate priceFrom;
    private LocalDate priceTo;
    private LocalDateTime calculatedAt;
    private LocalDateTime syncedAt;
    private Long analysisVersion;

    public boolean sameAnalysis(StockRiskGradeDTO other) {
        return var975.compareTo(other.var975) == 0
                && riskGrade.equals(other.riskGrade)
                && volatility.compareTo(other.volatility) == 0
                && priceFrom.equals(other.priceFrom)
                && priceTo.equals(other.priceTo)
                && calculatedAt.equals(other.calculatedAt);
    }
}
