package com.app.maria.domain.targetproduct.api;

import com.app.maria.domain.targetproduct.dto.request.TargetProductSearchRequestDTO;
import com.app.maria.domain.targetproduct.dto.response.TargetProductJudgementPageResponseDTO;
import com.app.maria.domain.targetproduct.dto.response.TargetProductSummaryResponseDTO;
import com.app.maria.domain.targetproduct.service.TargetProductService;
import com.app.maria.domain.targetproduct.type.StockType;
import com.app.maria.domain.targetproduct.type.TradeType;
import com.app.maria.global.response.ApiResponseDTO;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/target-products")
@PreAuthorize("hasAnyRole('ADMIN', 'SETTLEMENT', 'REVIEWER', 'VIEWER')")
public class TargetProductApi {

    private final TargetProductService targetProductService;

    @GetMapping
    public ResponseEntity<ApiResponseDTO<TargetProductJudgementPageResponseDTO>> getJudgements(
            @RequestParam(required = false) String customerName,
            @RequestParam(required = false) StockType stockType,
            @RequestParam(required = false) Boolean isTarget,
            @RequestParam(required = false) TradeType tradeType,
            @RequestParam(required = false) Boolean todayOnly,
            @RequestParam(required = false) Boolean inheritanceGiftOnly,
            @RequestParam(defaultValue = "0") @PositiveOrZero int page,
            @RequestParam(defaultValue = "20") @Positive int size) {
        TargetProductSearchRequestDTO request =
                TargetProductSearchRequestDTO.builder()
                        .customerName(customerName)
                        .stockType(stockType)
                        .isTarget(isTarget)
                        .tradeType(tradeType)
                        .todayOnly(todayOnly)
                        .inheritanceGiftOnly(inheritanceGiftOnly)
                        .page(page)
                        .size(size)
                        .build();
        TargetProductJudgementPageResponseDTO result =
                new TargetProductJudgementPageResponseDTO(
                        targetProductService.getJudgements(request));
        return ResponseEntity.ok(ApiResponseDTO.of("외부 순매수 판정 목록 조회 성공", result));
    }

    @GetMapping("/summary")
    public ResponseEntity<ApiResponseDTO<TargetProductSummaryResponseDTO>> getSummary() {
        TargetProductSummaryResponseDTO result =
                new TargetProductSummaryResponseDTO(targetProductService.getSummary());
        return ResponseEntity.ok(ApiResponseDTO.of("외부 순매수 판정 요약 조회 성공", result));
    }
}
