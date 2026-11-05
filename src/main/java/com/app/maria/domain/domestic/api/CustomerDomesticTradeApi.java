package com.app.maria.domain.domestic.api;

import com.app.maria.domain.domestic.dto.response.CustomerDomesticTradeResponseDTO;
import com.app.maria.domain.domestic.service.CustomerDomesticTradeService;
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
public class CustomerDomesticTradeApi {

    private final CustomerDomesticTradeService customerDomesticTradeService;

    @GetMapping("/domestic-trades")
    public ResponseEntity<ApiResponseDTO<PageResponseDTO<CustomerDomesticTradeResponseDTO>>>
            getTrades(
                    @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
                    @RequestParam(defaultValue = "0") int page,
                    @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "국내 매매 내역 조회 성공",
                        customerDomesticTradeService.getTrades(
                                principal.customerId(), page, size)));
    }
}
