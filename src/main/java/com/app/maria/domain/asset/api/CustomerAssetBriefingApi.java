package com.app.maria.domain.asset.api;

import com.app.maria.domain.asset.dto.response.CustomerAssetBriefingResponseDTO;
import com.app.maria.domain.asset.service.AssetBriefingService;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/customer/assets")
public class CustomerAssetBriefingApi {
    private final AssetBriefingService assetBriefingService;

    @GetMapping("/briefing")
    public ResponseEntity<ApiResponseDTO<CustomerAssetBriefingResponseDTO>> getBriefing(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "자산 브리핑 조회 성공", assetBriefingService.getBriefing(principal.customerId())));
    }
}
