package com.app.maria.domain.stockpackage.dto.request;

import com.app.maria.domain.domestic.type.DomesticOrderSide;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 증권사 거래 생성기가 보내는 "이 고객이 사거나 판다". 종목·수량은 maria가 고르므로 받지 않는다. */
public record InternalDomesticOrderRequestDTO(
        @NotBlank @Pattern(regexp = "[0-9a-fA-F]{64}") String ciHash,
        @NotNull DomesticOrderSide side,
        @NotBlank @Size(max = 60) @Pattern(regexp = "[A-Za-z0-9-]+") String idempotencyKey) {}
