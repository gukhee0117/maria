package com.app.maria.domain.domestic.api;

import com.app.maria.domain.domestic.dto.request.DomesticInvestmentSearchRequestDTO;
import com.app.maria.domain.domestic.dto.response.*;
import com.app.maria.domain.domestic.service.DomesticInvestmentService;
import com.app.maria.domain.domestic.type.DomesticStockStatus;
import com.app.maria.global.response.ApiResponseDTO;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/admin/domestic-investments")
public class DomesticInvestmentApi {

    private final DomesticInvestmentService domesticInvestmentService;

    @PreAuthorize("hasAnyRole('ADMIN', 'SETTLEMENT', 'REVIEWER', 'VIEWER')")
    @GetMapping
    public ResponseEntity<ApiResponseDTO<DomesticInvestmentPageResponseDTO>> getInvestments(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) DomesticStockStatus status,
            @RequestParam(required = false) Boolean hasRestrictedHolding,
            @RequestParam(required = false) Boolean hasUnpurchasableHolding,
            @RequestParam(required = false) Boolean hasRecentBuy,
            @RequestParam(required = false) Integer recentBuyDays,
            @RequestParam(defaultValue = "0") @PositiveOrZero int page,
            @RequestParam(defaultValue = "20") @Positive int size) {
        DomesticInvestmentSearchRequestDTO request =
                DomesticInvestmentSearchRequestDTO.builder()
                        .keyword(keyword)
                        .status(status)
                        .hasRestrictedHolding(hasRestrictedHolding)
                        .hasUnpurchasableHolding(hasUnpurchasableHolding)
                        .hasRecentBuy(hasRecentBuy)
                        .recentBuyDays(recentBuyDays)
                        .page(page)
                        .size(size)
                        .build();
        DomesticInvestmentPageResponseDTO result =
                new DomesticInvestmentPageResponseDTO(
                        domesticInvestmentService.getInvestments(request));
        return ResponseEntity.ok(ApiResponseDTO.of("국내투자 현황 목록 조회 성공", result));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SETTLEMENT', 'REVIEWER', 'VIEWER')")
    @GetMapping("/{accountId}")
    public ResponseEntity<ApiResponseDTO<DomesticAccountDetailResponseDTO>> getAccountDetail(
            @PathVariable @Positive Long accountId) {
        DomesticAccountDetailResponseDTO result =
                new DomesticAccountDetailResponseDTO(
                        domesticInvestmentService.getAccountDetail(accountId));
        return ResponseEntity.ok(ApiResponseDTO.of("국내투자 현황 상세 조회 성공", result));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SETTLEMENT', 'REVIEWER', 'VIEWER')")
    @GetMapping("/summary")
    public ResponseEntity<ApiResponseDTO<DomesticInvestmentSummaryResponseDTO>> getSummary(
            @RequestParam(defaultValue = "7") @Positive int days) {
        return ResponseEntity.ok(
                ApiResponseDTO.of("국내투자 현황 요약 조회 성공", domesticInvestmentService.getSummary(days)));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SETTLEMENT', 'REVIEWER', 'VIEWER')")
    @GetMapping("/restricted-holdings")
    public ResponseEntity<ApiResponseDTO<List<DomesticRestrictedHoldingResponseDTO>>>
            getRestrictedHoldings() {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "거래제한·정지 종목 목록 조회 성공", domesticInvestmentService.getRestrictedHoldings()));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SETTLEMENT', 'REVIEWER', 'VIEWER')")
    @GetMapping("/cash-heavy-accounts")
    public ResponseEntity<ApiResponseDTO<DomesticCashHeavyPageResponseDTO>> getCashHeavyAccounts(
            @RequestParam(defaultValue = "0") @PositiveOrZero int page,
            @RequestParam(defaultValue = "20") @Positive int size) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "예탁금 비중 높은 계좌 목록 조회 성공",
                        domesticInvestmentService.getCashHeavyAccounts(page, size)));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SETTLEMENT', 'REVIEWER', 'VIEWER')")
    @GetMapping("/unpurchasable-holdings")
    public ResponseEntity<ApiResponseDTO<List<DomesticUnpurchasableHoldingResponseDTO>>>
            getUnpurchasableHoldings() {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "매수불가 종목 목록 조회 성공", domesticInvestmentService.getUnpurchasableHoldings()));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SETTLEMENT', 'REVIEWER', 'VIEWER')")
    @GetMapping("/recent-buy-accounts")
    public ResponseEntity<ApiResponseDTO<List<DomesticAccountLiteResponseDTO>>>
            getRecentBuyAccounts(
                    @RequestParam(defaultValue = "7") @Positive int days,
                    @RequestParam boolean hasRecentBuy) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "최근 매수 계좌 목록 조회 성공",
                        domesticInvestmentService.getRecentBuyAccounts(days, hasRecentBuy)));
    }
}
