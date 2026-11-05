package com.app.maria.domain.sellorder.api;

import com.app.maria.domain.sellorder.dto.request.CustomerSellOrderRequestDTO;
import com.app.maria.domain.sellorder.dto.response.CustomerSellOrderResponseDTO;
import com.app.maria.domain.sellorder.dto.response.CustomerSellOrderResultResponseDTO;
import com.app.maria.domain.sellorder.service.CustomerSellOrderService;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.response.PageResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/customer")
public class CustomerSellOrderApi {

    private final CustomerSellOrderService customerSellOrderService;

    @PostMapping("/sell-orders")
    public ResponseEntity<ApiResponseDTO<CustomerSellOrderResultResponseDTO>> placeSellOrder(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @Valid @RequestBody CustomerSellOrderRequestDTO request) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "매도 신청 처리 성공",
                        customerSellOrderService.placeOrder(principal.customerId(), request)));
    }

    @GetMapping("/sell-orders")
    public ResponseEntity<ApiResponseDTO<PageResponseDTO<CustomerSellOrderResponseDTO>>>
            getSellOrders(
                    @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
                    @RequestParam(defaultValue = "0") int page,
                    @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "매도 내역 조회 성공",
                        customerSellOrderService.getSellOrders(
                                principal.customerId(), page, size)));
    }
}
