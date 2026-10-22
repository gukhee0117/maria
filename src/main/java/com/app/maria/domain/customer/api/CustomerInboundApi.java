package com.app.maria.domain.customer.api;

import com.app.maria.domain.customer.dto.response.CustomerForeignProductResponseDTO;
import com.app.maria.domain.customer.dto.response.CustomerInboundCandidatesResponseDTO;
import com.app.maria.domain.customer.dto.response.CustomerInboundDetailResponseDTO;
import com.app.maria.domain.customer.dto.response.CustomerInboundHistoryResponseDTO;
import com.app.maria.domain.customer.service.CustomerInboundQueryService;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.response.PageResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/customer")
public class CustomerInboundApi {

    private final CustomerInboundQueryService customerInboundQueryService;

    @GetMapping("/foreign-products")
    public ResponseEntity<ApiResponseDTO<List<CustomerForeignProductResponseDTO>>> getForeignProducts(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return ResponseEntity.ok(ApiResponseDTO.of(
                "입고 가능 종목 조회 성공",
                customerInboundQueryService.getForeignProducts(principal.customerId())));
    }

    @GetMapping("/inbounds/candidates")
    public ResponseEntity<ApiResponseDTO<CustomerInboundCandidatesResponseDTO>> getCandidates(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @RequestParam Long foreignProductId) {
        return ResponseEntity.ok(ApiResponseDTO.of(
                "입고 후보 조회 성공",
                customerInboundQueryService.getCandidates(principal.customerId(), foreignProductId)));
    }

    @GetMapping("/inbounds")
    public ResponseEntity<ApiResponseDTO<PageResponseDTO<CustomerInboundHistoryResponseDTO>>> getHistory(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponseDTO.of(
                "입고 이력 조회 성공",
                customerInboundQueryService.getHistory(principal.customerId(), page, size)));
    }

    @GetMapping("/inbounds/{inboundId}")
    public ResponseEntity<ApiResponseDTO<CustomerInboundDetailResponseDTO>> getDetail(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable Long inboundId) {
        return ResponseEntity.ok(ApiResponseDTO.of(
                "입고 상세 조회 성공",
                customerInboundQueryService.getDetail(principal.customerId(), inboundId)));
    }
}
