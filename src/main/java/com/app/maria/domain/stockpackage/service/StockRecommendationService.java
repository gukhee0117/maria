package com.app.maria.domain.stockpackage.service;

import com.app.maria.domain.stockpackage.dto.request.StockRecommendationAskRequestDTO;
import com.app.maria.domain.stockpackage.dto.response.StockRecommendationAskResponseDTO;
import com.app.maria.domain.stockpackage.dto.response.StockRecommendationResponseDTO;

public interface StockRecommendationService {

    /**
     * 내 투자성향 범위 안에서 RIA 계좌로 살 수 있는 상품을 추천한다. filter는 ALL·STOCK·FUND이고 limit은 1~12이다. 진단이 없거나 만료되면 빈
     * 목록과 profileAvailable=false를 준다.
     */
    StockRecommendationResponseDTO recommend(Long customerId, String filter, int limit);

    /**
     * 추천 종목에 대한 질문에 답한다. 답은 서버가 가진 사실(등급·변동성·테마)만으로 만든다. 질문에 테마가 있으면 그 테마로 좁혀 설문 허용 범위 안 상품에서 다시
     * 고르고, 범위 밖 상품은 추천하지 않고 개수만 알려 후보 화면에서 직접 확인하게 한다.
     */
    StockRecommendationAskResponseDTO ask(
            Long customerId, StockRecommendationAskRequestDTO request);
}
