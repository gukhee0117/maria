package com.app.maria.domain.risk.dto;

import com.app.maria.domain.risk.type.RiskDataStatus;
import java.math.BigDecimal;
import java.time.LocalDate;

public record UsableRiskGradeDTO(
        Long domesticProductId,
        String ticker,
        BigDecimal var975,
        int riskGrade,
        BigDecimal volatility,
        LocalDate priceFrom,
        LocalDate priceTo,
        long analysisVersion,
        RiskDataStatus status) {}
