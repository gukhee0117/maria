package com.app.maria.domain.stockpackage.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 추천 종목에 대한 질문. 질문은 100자까지이고, focusTicker는 고객이 펼쳐 본 추천 종목이다. */
public record StockRecommendationAskRequestDTO(
        @NotBlank @Size(max = 100) String question, @Size(max = 12) String focusTicker) {}
