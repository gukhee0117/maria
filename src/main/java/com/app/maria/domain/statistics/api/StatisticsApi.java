package com.app.maria.domain.statistics.api;

import com.app.maria.domain.statistics.dto.request.StatisticsFilterRequestDTO;
import com.app.maria.domain.statistics.dto.response.AccountBenefitStatResponseDTO;
import com.app.maria.domain.statistics.dto.response.AgeInvestmentStatResponseDTO;
import com.app.maria.domain.statistics.dto.response.FxExchangeStatResponseDTO;
import com.app.maria.domain.statistics.dto.response.ProductPurchaseStatResponseDTO;
import com.app.maria.domain.statistics.dto.response.ReliefRateStatResponseDTO;
import com.app.maria.domain.statistics.service.StatisticsService;
import com.app.maria.global.response.ApiResponseDTO;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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
@RequestMapping("/api/admin/statistics")
@PreAuthorize("hasAnyRole('ADMIN', 'SETTLEMENT', 'REVIEWER', 'VIEWER')")
public class StatisticsApi {

    private final StatisticsService statisticsService;

    @GetMapping("/age-investment")
    public ResponseEntity<ApiResponseDTO<List<AgeInvestmentStatResponseDTO>>> getAgeInvestmentStats(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String productName,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate endDate) {
        List<AgeInvestmentStatResponseDTO> result =
                statisticsService
                        .getAgeInvestmentStats(
                                buildRequest(keyword, productName, startDate, endDate))
                        .stream()
                        .map(AgeInvestmentStatResponseDTO::new)
                        .toList();
        return ResponseEntity.ok(ApiResponseDTO.of("나이대별 투자 현황 조회 성공", result));
    }

    @GetMapping("/product-purchase")
    public ResponseEntity<ApiResponseDTO<List<ProductPurchaseStatResponseDTO>>>
            getProductPurchaseStats(
                    @RequestParam(required = false) String keyword,
                    @RequestParam(required = false) String productName,
                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                            LocalDate startDate,
                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                            LocalDate endDate) {
        List<ProductPurchaseStatResponseDTO> result =
                statisticsService
                        .getProductPurchaseStats(
                                buildRequest(keyword, productName, startDate, endDate))
                        .stream()
                        .map(ProductPurchaseStatResponseDTO::new)
                        .toList();
        return ResponseEntity.ok(ApiResponseDTO.of("종목별 매수 현황 조회 성공", result));
    }

    @GetMapping("/fx-exchange")
    public ResponseEntity<ApiResponseDTO<List<FxExchangeStatResponseDTO>>> getFxExchangeStats(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate endDate) {
        List<FxExchangeStatResponseDTO> result =
                statisticsService
                        .getFxExchangeStats(buildRequest(keyword, null, startDate, endDate))
                        .stream()
                        .map(FxExchangeStatResponseDTO::new)
                        .toList();
        return ResponseEntity.ok(ApiResponseDTO.of("외화유입 · 원화환전 통계 조회 성공", result));
    }

    @GetMapping("/account-benefit")
    public ResponseEntity<ApiResponseDTO<List<AccountBenefitStatResponseDTO>>>
            getAccountBenefitStats(@RequestParam(required = false) String keyword) {
        List<AccountBenefitStatResponseDTO> result =
                statisticsService
                        .getAccountBenefitStats(buildRequest(keyword, null, null, null))
                        .stream()
                        .map(AccountBenefitStatResponseDTO::new)
                        .toList();
        return ResponseEntity.ok(ApiResponseDTO.of("세제혜택 상태 분포 조회 성공", result));
    }

    @GetMapping("/relief-rate")
    public ResponseEntity<ApiResponseDTO<List<ReliefRateStatResponseDTO>>> getReliefRateStats(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate endDate) {
        List<ReliefRateStatResponseDTO> result =
                statisticsService
                        .getReliefRateStats(buildRequest(keyword, null, startDate, endDate))
                        .stream()
                        .map(ReliefRateStatResponseDTO::new)
                        .toList();
        return ResponseEntity.ok(ApiResponseDTO.of("감면율 구간별 매도금액 분포 조회 성공", result));
    }

    private StatisticsFilterRequestDTO buildRequest(
            String keyword, String productName, LocalDate startDate, LocalDate endDate) {
        return StatisticsFilterRequestDTO.builder()
                .keyword(keyword)
                .productName(productName)
                .startDate(startDate)
                .endDate(endDate)
                .build();
    }
}
