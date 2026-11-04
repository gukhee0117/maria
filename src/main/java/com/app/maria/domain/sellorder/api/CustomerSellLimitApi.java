package com.app.maria.domain.sellorder.api;

import com.app.maria.domain.sellorder.dto.response.SellLimitStatusDTO;
import com.app.maria.domain.sellorder.service.SellLimitService;
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
@RequestMapping("/api/customer")
public class CustomerSellLimitApi {

    private final SellLimitService sellLimitService;

    @GetMapping("/sell-limit")
    public ResponseEntity<ApiResponseDTO<SellLimitStatusDTO>> getSellLimit(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "매도 한도 조회 성공",
                        sellLimitService.getStatusByCustomerId(principal.customerId())));
    }
}
