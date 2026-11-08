package com.app.maria.domain.stockpackage.api;

import com.app.maria.domain.stockpackage.dto.response.StockPriceHistoryResponseDTO;
import com.app.maria.domain.stockpackage.service.StockPriceHistoryService;
import com.app.maria.global.response.ApiResponseDTO;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 국내주식 일별 종가 이력(차트용). 증권사 시세 API를 중계하고, 기준일은 서버의 업무일이다. */
@RestController
@RequestMapping("/api/customer/stock-packages/price-history")
@RequiredArgsConstructor
public class StockPriceHistoryApi {
    private final StockPriceHistoryService stockPriceHistoryService;

    @GetMapping
    @Operation(
            summary =
                    "국내주식 일별 종가 이력 (최대 20종목, 쉼표로 구분, days 기본 30·1~60으로 맞춤). 업무일까지(업무일 당일 포함) 저장된 종가만 오며 현재가와 다를 수 있다. 이력이 없는 종목은 available=false")
    public ApiResponseDTO<List<StockPriceHistoryResponseDTO>> history(
            @RequestParam List<String> tickers, @RequestParam(defaultValue = "30") int days) {
        return ApiResponseDTO.of("종가 이력 조회 성공", stockPriceHistoryService.getHistory(tickers, days));
    }
}
