package com.app.maria.domain.stockpackage.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 추천 질문 답변. ticker가 있으면 화면이 그 종목의 "패키지에 담기" 버튼을 붙인다. */
@Getter
@AllArgsConstructor
public class StockRecommendationAskResponseDTO {
    private final String answer;
    private final String ticker;
    private final boolean aiGenerated;
    private final String disclaimer;
}
