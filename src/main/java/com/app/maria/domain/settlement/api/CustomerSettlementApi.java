package com.app.maria.domain.settlement.api;

import com.app.maria.domain.settlement.dto.response.CustomerSettlementResponseDTO;
import com.app.maria.domain.settlement.service.CustomerSettlementService;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.response.PageResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/customer")
public class CustomerSettlementApi {

    private final CustomerSettlementService customerSettlementService;

    @GetMapping("/settlements")
    public ResponseEntity<ApiResponseDTO<PageResponseDTO<CustomerSettlementResponseDTO>>>
            getSettlements(
                    @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
                    @RequestParam(defaultValue = "0") int page,
                    @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "정산 조회 성공",
                        customerSettlementService.getSettlements(
                                principal.customerId(), page, size)));
    }
}
