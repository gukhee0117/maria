package com.app.maria.domain.stockpackage.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 추천 종목에 대한 질문. 질문은 100자까지이고, focusTicker는 고객이 펼쳐 본 추천 종목이다. limit은 화면이 보여 주는 추천 개수(1~12)이고, 없으면
 * 6이다. 화면과 같은 종목으로 답하려면 화면과 같은 값을 보낸다.
 */
public record StockRecommendationAskRequestDTO(
        @NotBlank @Size(max = 100) String question,
        @Size(max = 12) String focusTicker,
        @Min(1) @Max(12) Integer limit) {}
