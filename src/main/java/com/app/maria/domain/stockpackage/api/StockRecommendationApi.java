package com.app.maria.domain.stockpackage.api;

import com.app.maria.domain.stockpackage.dto.request.StockRecommendationAskRequestDTO;
import com.app.maria.domain.stockpackage.dto.response.StockRecommendationAskResponseDTO;
import com.app.maria.domain.stockpackage.dto.response.StockRecommendationResponseDTO;
import com.app.maria.domain.stockpackage.service.StockRecommendationService;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 성향별 추천. 내 투자성향 범위 안에서 RIA 계좌로 살 수 있는 상품을 이유와 함께 보여주고, 추천 종목에 대한 질문에 답한다. */
@RestController
@RequestMapping("/api/customer/stock-packages/recommendations")
@RequiredArgsConstructor
public class StockRecommendationApi {
    private final StockRecommendationService stockRecommendationService;

    @GetMapping
    @Operation(
            summary =
                    "오늘의 종목 추천. filter(ALL·STOCK·FUND, 기본 ALL)와 limit(1~12, 기본 3)로 고른다. 설문 투자성향 진단이 없거나 만료되면 빈 목록이고 profileAvailable=false다")
    public ApiResponseDTO<StockRecommendationResponseDTO> recommend(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @RequestParam(defaultValue = "ALL") String filter,
            @RequestParam(defaultValue = "3") int limit) {
        return ApiResponseDTO.of(
                "추천 조회 성공",
                stockRecommendationService.recommend(principal.customerId(), filter, limit));
    }

    @PostMapping("/ask")
    @Operation(
            summary =
                    "추천 종목에 대해 끼루에게 묻기 (CSRF 필수). 서버가 가진 사실(등급·변동성·테마)로 답하고, 규칙으로 답하지 못하는 질문만 AI가 그 사실 안에서 답한다")
    public ApiResponseDTO<StockRecommendationAskResponseDTO> ask(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @Valid @RequestBody StockRecommendationAskRequestDTO request) {
        return ApiResponseDTO.of(
                "추천 질문 답변 완료", stockRecommendationService.ask(principal.customerId(), request));
    }
}
