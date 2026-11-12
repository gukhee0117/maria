package com.app.maria.domain.stockpackage.api;

import com.app.maria.domain.stockpackage.dto.response.StockTendencyResponseDTO;
import com.app.maria.domain.stockpackage.service.StockTendencyService;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 설문 투자성향. 고객은 로그인한 본인뿐이다. */
@RestController
@RequestMapping("/api/customer/stock-packages/tendency")
@RequiredArgsConstructor
public class StockTendencyApi {
    private final StockTendencyService stockTendencyService;

    @GetMapping
    @Operation(summary = "설문 투자성향(허용 위험등급 범위) 조회")
    public ApiResponseDTO<StockTendencyResponseDTO> tendency(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return ApiResponseDTO.of(
                "투자성향 조회 성공", stockTendencyService.getTendency(principal.customerId()));
    }
}
