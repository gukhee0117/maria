package com.app.maria.domain.tax.api;

import com.app.maria.domain.tax.dto.response.CustomerTaxBriefingResponseDTO;
import com.app.maria.domain.tax.service.TaxCalculationService;
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
@RequestMapping("/api/customer/tax")
public class CustomerTaxBriefingApi {

    private final TaxCalculationService taxCalculationService;

    @GetMapping("/briefing")
    public ResponseEntity<ApiResponseDTO<CustomerTaxBriefingResponseDTO>> getBriefing(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "절세 브리핑 조회 성공",
                        taxCalculationService.getCustomerTaxBriefing(principal.customerId())));
    }
}
