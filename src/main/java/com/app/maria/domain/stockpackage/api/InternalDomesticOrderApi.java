package com.app.maria.domain.stockpackage.api;

import com.app.maria.domain.stockpackage.dto.request.InternalDomesticOrderRequestDTO;
import com.app.maria.domain.stockpackage.dto.response.GeneratorOrderResponseDTO;
import com.app.maria.domain.stockpackage.service.GeneratorOrderService;
import com.app.maria.global.response.ApiResponseDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 증권사 거래 생성기 전용. 서비스 간 비밀 헤더가 있어야 하고 nginx가 외부 접근을 막는다. */
@RestController
@RequestMapping("/api/internal/domestic-orders")
@RequiredArgsConstructor
public class InternalDomesticOrderApi {
    private final GeneratorOrderService service;

    @PostMapping
    public ApiResponseDTO<GeneratorOrderResponseDTO> order(
            @Valid @RequestBody InternalDomesticOrderRequestDTO request) {
        return ApiResponseDTO.of(
                "주문 처리 성공",
                service.order(request.ciHash(), request.side(), request.idempotencyKey()));
    }
}
