package com.app.maria.domain.risk.dto;

public record RiskGradeClassificationDTO(
        Long domesticProductId, String ticker, String name, int riskGrade, String basis) {}
