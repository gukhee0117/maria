package com.app.maria.domain.sellorder.api;

import com.app.maria.domain.sellorder.dto.SellOrderDetailDTO;
import com.app.maria.domain.sellorder.dto.SellOrderHistoryDTO;
import com.app.maria.domain.sellorder.dto.SellOrderSummaryDTO;
import com.app.maria.domain.sellorder.dto.request.SellOrderRequestDTO;
import com.app.maria.domain.sellorder.dto.response.SellOrderDetailResponseDTO;
import com.app.maria.domain.sellorder.dto.response.SellOrderHistoryResponseDTO;
import com.app.maria.domain.sellorder.dto.response.SellOrderResponseDTO;
import com.app.maria.domain.sellorder.dto.response.SellOrderSummaryResponseDTO;
import com.app.maria.domain.sellorder.service.SellOrderService;
import com.app.maria.domain.sellorder.type.SellOrderStatus;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.response.PageResponseDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/admin/sell-orders")
@PreAuthorize("hasAnyRole('ADMIN','REVIEWER','SETTLEMENT','VIEWER')")
public class SellOrderApi {

    private final SellOrderService sellOrderService;

    @PreAuthorize("hasRole('SETTLEMENT')")
    @PostMapping
    public ResponseEntity<ApiResponseDTO<List<SellOrderResponseDTO>>> placeSellOrder(
            @AuthenticationPrincipal Long actorAdminId,
            @Valid @RequestBody SellOrderRequestDTO request) {
        // placeSellOrder는 항상 최소 1건을 반환하며 리스트 내 상태는 REJECTED 단독 또는 EXECUTED로 균일하다는 Service 계층의 암묵적
        // 불변식에 의존함.
        // 이 전제가 깨지면(빈 리스트/상태 혼재) 여기서 조용히 500이 날 수 있음.
        List<SellOrderResponseDTO> responseDTOs =
                sellOrderService.placeSellOrder(actorAdminId, request);
        String message =
                switch (responseDTOs.get(0).getStatus()) {
                    case EXECUTED -> "매도 주문이 체결되었습니다.";
                    case REJECTED -> "매도 한도 초과로 거부되었습니다.";
                    case RECEIVED -> "매도 주문이 접수되었습니다.";
                };
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseDTO.of(message, responseDTOs));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponseDTO<SellOrderResponseDTO>> getSellOrder(
            @PathVariable Long orderId) {
        SellOrderResponseDTO responseDTO = sellOrderService.getSellOrder(orderId);
        return ResponseEntity.ok(ApiResponseDTO.of("매도 주문 조회에 성공하였습니다.", responseDTO));
    }

    @GetMapping
    public ResponseEntity<ApiResponseDTO<List<SellOrderResponseDTO>>> getAllSellOrders(
            @RequestParam Long accountId) {
        List<SellOrderResponseDTO> list = sellOrderService.getSellOrderByAccount(accountId);
        return ResponseEntity.ok(ApiResponseDTO.of("계좌 매도 주문 조회에 성공하였습니다.", list));
    }

    @GetMapping("/{orderId}/detail")
    public ResponseEntity<ApiResponseDTO<SellOrderDetailResponseDTO>> getSellOrderDetail(
            @PathVariable Long orderId) {
        SellOrderDetailDTO detail = sellOrderService.getSellOrderDetail(orderId);
        return ResponseEntity.ok(
                ApiResponseDTO.of("매도 주문 상세 조회에 성공하였습니다.", new SellOrderDetailResponseDTO(detail)));
    }

    @GetMapping("/summary")
    public ResponseEntity<ApiResponseDTO<SellOrderSummaryResponseDTO>> getSellOrderSummary() {
        SellOrderSummaryDTO summary = sellOrderService.getSellOrderSummary();
        return ResponseEntity.ok(
                ApiResponseDTO.of("매도 · 환전 요약 조회 성공", new SellOrderSummaryResponseDTO(summary)));
    }

    @GetMapping("/history")
    public ResponseEntity<ApiResponseDTO<PageResponseDTO<SellOrderHistoryResponseDTO>>>
            getSellOrderHistory(
                    @RequestParam(required = false) String keyword,
                    @RequestParam(required = false) SellOrderStatus status,
                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                            LocalDate startDate,
                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                            LocalDate endDate,
                    @RequestParam(defaultValue = "0") @PositiveOrZero int page,
                    @RequestParam(defaultValue = "20") @Positive int size) {
        PageResponseDTO<SellOrderHistoryDTO> result =
                sellOrderService.getSellOrderHistory(
                        keyword, status, startDate, endDate, page, size);
        List<SellOrderHistoryResponseDTO> content =
                result.getContent().stream().map(SellOrderHistoryResponseDTO::new).toList();
        PageResponseDTO<SellOrderHistoryResponseDTO> response =
                PageResponseDTO.of(
                        content, result.getTotalCount(), result.getPage(), result.getSize());
        return ResponseEntity.ok(ApiResponseDTO.of("매도 · 환전 내역 조회 성공", response));
    }
}
