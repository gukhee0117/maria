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

/** 설문 투자성향과 거래에서 본 경향. 고객은 로그인한 본인뿐이다. */
@RestController
@RequestMapping("/api/customer/stock-packages/tendency")
@RequiredArgsConstructor
public class StockTendencyApi {
    private final StockTendencyService stockTendencyService;

    @GetMapping
    @Operation(
            summary = "설문 투자성향과 거래에서 본 경향 비교. 거래에서 본 경향은 RIA 계좌 체결 주문으로 계산한 참고 정보이고 공식 투자성향이 아니다")
    public ApiResponseDTO<StockTendencyResponseDTO> tendency(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return ApiResponseDTO.of(
                "투자 경향 조회 성공", stockTendencyService.getTendency(principal.customerId()));
    }
}
