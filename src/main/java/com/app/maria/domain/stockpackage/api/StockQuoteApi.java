package com.app.maria.domain.stockpackage.api;

import com.app.maria.domain.stockpackage.dto.response.StockQuoteResponseDTO;
import com.app.maria.domain.stockpackage.service.StockQuoteService;
import com.app.maria.global.response.ApiResponseDTO;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 국내주식 현재가. 저장·캐시 없이 요청마다 KIS에서 가져온다. */
@RestController
@RequestMapping("/api/customer/stock-packages/quotes")
@RequiredArgsConstructor
public class StockQuoteApi {
    private final StockQuoteService stockQuoteService;

    @GetMapping
    @Operation(
            summary =
                    "국내주식 현재가 (최대 20종목, 쉼표로 구분). 시세를 받지 못한 종목은 available=false로 오며, 그 종목으로는 금액 계산·주문을 하지 않는다")
    public ApiResponseDTO<List<StockQuoteResponseDTO>> quotes(@RequestParam List<String> tickers) {
        return ApiResponseDTO.of("현재가 조회 성공", stockQuoteService.getQuotes(tickers));
    }
}
