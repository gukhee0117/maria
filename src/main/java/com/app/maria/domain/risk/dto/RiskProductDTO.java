package com.app.maria.domain.risk.dto;

import com.app.maria.domain.domestic.type.Type;

public record RiskProductDTO(Long domesticProductId, String ticker, String name, Type type) {}
