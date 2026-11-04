package com.app.maria.domain.stockpackage.api;

import com.app.maria.domain.stockpackage.dto.request.StockPackageCheckRequestDTO;
import com.app.maria.domain.stockpackage.dto.response.StockPackageCheckResponseDTO;
import com.app.maria.domain.stockpackage.service.StockPackageCheckService;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 패키지 점검. 저장하지 않고 요청한 구성만 계산한다. 투자 권유는 하지 않고 계산된 사실만 돌려준다. */
@RestController
@RequestMapping("/api/customer/stock-packages/check")
@RequiredArgsConstructor
public class StockPackageCheckApi {
    private final StockPackageCheckService stockPackageCheckService;

    @PostMapping
    @Operation(
            summary =
                    "패키지 점검 (CSRF 필수). 종목·비중(합 100)으로 위험등급 가중평균, 성향 범위 밖 종목, 집중도, 변동성을 계산한다. 저장하지 않는다")
    public ApiResponseDTO<StockPackageCheckResponseDTO> check(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @Valid @RequestBody StockPackageCheckRequestDTO request) {
        return ApiResponseDTO.of(
                "패키지 점검 성공", stockPackageCheckService.check(principal.customerId(), request));
    }
}
