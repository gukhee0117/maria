package com.app.maria.domain.stockpackage.api;

import com.app.maria.domain.stockpackage.dto.request.StockOrderRequestDTO;
import com.app.maria.domain.stockpackage.dto.response.StockOrderFeeResponseDTO;
import com.app.maria.domain.stockpackage.dto.response.StockOrderResponseDTO;
import com.app.maria.domain.stockpackage.dto.response.StockOrderResultDTO;
import com.app.maria.domain.stockpackage.dto.response.StockPackageAllHoldingsResponseDTO;
import com.app.maria.domain.stockpackage.dto.response.StockPackageHoldingResponseDTO;
import com.app.maria.domain.stockpackage.service.StockOrderService;
import com.app.maria.domain.stockpackage.service.StockPackageHoldingService;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 패키지 시장가 주문. 고객은 로그인한 본인뿐이고, 패키지에 담은 종목만 주문할 수 있다. */
@RestController
@RequestMapping("/api/customer/stock-packages")
@RequiredArgsConstructor
public class StockOrderApi {
    private final StockOrderService stockOrderService;
    private final StockPackageHoldingService stockPackageHoldingService;

    @GetMapping("/order-fees")
    @Operation(summary = "오늘 기준 수수료율·증권거래세율(%). 주문 창의 예상 금액 계산용이고 실제 금액은 체결할 때 서버가 계산한다")
    public ApiResponseDTO<StockOrderFeeResponseDTO> fees(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return ApiResponseDTO.of("요율 조회 성공", stockOrderService.getFees(principal.customerId()));
    }

    @GetMapping("/orders")
    @Operation(summary = "내 모든 패키지의 주문 내역 (체결·거부, 최신순, 최대 200건). 패키지 이름이 함께 온다")
    public ApiResponseDTO<List<StockOrderResultDTO>> allOrders(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return ApiResponseDTO.of(
                "주문 내역 조회 성공", stockPackageHoldingService.getOrders(principal.customerId(), null));
    }

    @GetMapping("/holdings")
    @Operation(
            summary =
                    "내 모든 패키지의 보유 현황 (최근 만든 순). 담은 종목은 안 샀어도 수량 0으로 나온다. 현재가는 종목 합집합으로 한 번에 조회한다. 예수금·주문 가능 금액은 패키지가 없어도 온다")
    public ApiResponseDTO<StockPackageAllHoldingsResponseDTO> allHoldings(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return ApiResponseDTO.of(
                "보유 현황 조회 성공", stockPackageHoldingService.getAllHoldings(principal.customerId()));
    }

    @GetMapping("/{stockPackageId}/orders")
    @Operation(summary = "한 패키지의 주문 내역 (체결·거부, 최신순, 최대 200건)")
    public ApiResponseDTO<List<StockOrderResultDTO>> orders(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable Long stockPackageId) {
        return ApiResponseDTO.of(
                "주문 내역 조회 성공",
                stockPackageHoldingService.getOrders(principal.customerId(), stockPackageId));
    }

    @GetMapping("/{stockPackageId}/holdings")
    @Operation(
            summary =
                    "패키지 보유 현황. 이 패키지로 체결한 주문을 합산한 수량·평균매입가에 KIS 현재가로 평가금액·손익을 붙인다(수수료·세금 제외). 현재가를 못 받은 종목은 평가값이 null")
    public ApiResponseDTO<StockPackageHoldingResponseDTO> holdings(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable Long stockPackageId) {
        return ApiResponseDTO.of(
                "보유 현황 조회 성공",
                stockPackageHoldingService.getHoldings(principal.customerId(), stockPackageId));
    }

    @PostMapping("/{stockPackageId}/orders")
    @Operation(
            summary =
                    "패키지 시장가 주문 (CSRF 필수). 종목마다 체결 또는 거부 한 건씩 돌려준다. 같은 idempotencyKey로 다시 보내면 새로 체결하지 않고 처음 결과를 준다. 체결가는 KIS 현재가이고 수수료·증권거래세는 tax_rule로 계산한다")
    public ApiResponseDTO<StockOrderResponseDTO> place(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable Long stockPackageId,
            @Valid @RequestBody StockOrderRequestDTO request) {
        return ApiResponseDTO.of(
                "주문 처리 완료",
                stockOrderService.place(principal.customerId(), stockPackageId, request));
    }
}
