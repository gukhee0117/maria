package com.app.maria.domain.stockpackage.api;

import com.app.maria.domain.stockpackage.dto.request.StockPackageSaveRequestDTO;
import com.app.maria.domain.stockpackage.dto.response.StockPackageResponseDTO;
import com.app.maria.domain.stockpackage.dto.response.StockPackageSummaryResponseDTO;
import com.app.maria.domain.stockpackage.service.StockPackageService;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 고객 주식 패키지. 고객은 로그인한 본인뿐이고 요청에서 고객 ID를 받지 않는다. */
@RestController
@RequestMapping("/api/customer/stock-packages")
@RequiredArgsConstructor
public class StockPackageApi {
    private final StockPackageService stockPackageService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "패키지 만들기 (CSRF 필수). 종목이 있으면 비중 합 100과 성향 밖 종목 확인을 검증한다")
    public ApiResponseDTO<StockPackageResponseDTO> create(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @Valid @RequestBody StockPackageSaveRequestDTO request) {
        return ApiResponseDTO.of(
                "패키지 생성 성공", stockPackageService.create(principal.customerId(), request));
    }

    @GetMapping
    @Operation(summary = "내 패키지 목록")
    public ApiResponseDTO<List<StockPackageSummaryResponseDTO>> list(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return ApiResponseDTO.of("패키지 목록 조회 성공", stockPackageService.list(principal.customerId()));
    }

    @GetMapping("/{stockPackageId}")
    @Operation(summary = "패키지 상세 (종목별 위험등급과 내 성향 범위 밖 여부 포함)")
    public ApiResponseDTO<StockPackageResponseDTO> get(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable Long stockPackageId) {
        return ApiResponseDTO.of(
                "패키지 조회 성공", stockPackageService.get(principal.customerId(), stockPackageId));
    }

    @PutMapping("/{stockPackageId}")
    @Operation(summary = "패키지 수정 (CSRF 필수). 이름·총 투자금액·구성 종목을 통째로 바꾼다")
    public ApiResponseDTO<StockPackageResponseDTO> update(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable Long stockPackageId,
            @Valid @RequestBody StockPackageSaveRequestDTO request) {
        return ApiResponseDTO.of(
                "패키지 수정 성공",
                stockPackageService.update(principal.customerId(), stockPackageId, request));
    }

    @DeleteMapping("/{stockPackageId}")
    @Operation(summary = "패키지 삭제 (CSRF 필수). 주문 내역이 있으면 삭제할 수 없다")
    public ApiResponseDTO<Void> delete(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable Long stockPackageId) {
        stockPackageService.delete(principal.customerId(), stockPackageId);
        return ApiResponseDTO.of("패키지 삭제 성공", null);
    }
}
