package com.app.maria.domain.asset.api;

import com.app.maria.domain.asset.dto.response.CustomerAssetHoldingResponseDTO;
import com.app.maria.domain.asset.service.CustomerAssetHoldingsService;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/customer/assets")
public class CustomerAssetHoldingsApi {

    private final CustomerAssetHoldingsService customerAssetHoldingsService;

    @GetMapping("/holdings")
    public ResponseEntity<ApiResponseDTO<List<CustomerAssetHoldingResponseDTO>>> getHoldings(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "보유 종목 조회 성공",
                        customerAssetHoldingsService.getHoldings(principal.customerId())));
    }
}
