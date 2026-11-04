package com.app.maria.domain.stockpackage.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 말로 조건 찾기 요청. 입력은 60자까지다. */
public record StockConditionParseRequestDTO(@NotBlank @Size(max = 60) String text) {}
