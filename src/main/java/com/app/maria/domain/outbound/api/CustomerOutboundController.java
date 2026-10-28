package com.app.maria.domain.outbound.api;

import com.app.maria.domain.outbound.dto.request.CustomerOutboundRequestDTO;
import com.app.maria.domain.outbound.dto.response.CustomerOutboundResponseDTO;
import com.app.maria.domain.outbound.service.CustomerOutboundService;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customer/outbound")
@RequiredArgsConstructor
public class CustomerOutboundController {

    private final CustomerOutboundService customerOutboundService;

    @PostMapping
    public ResponseEntity<ApiResponseDTO<CustomerOutboundResponseDTO>> submit(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @Valid @RequestBody CustomerOutboundRequestDTO request) {
        CustomerOutboundResponseDTO response =
                customerOutboundService.submit(principal.customerId(), request);
        return ResponseEntity.ok(ApiResponseDTO.of("출고 신청 완료", response));
    }
}
