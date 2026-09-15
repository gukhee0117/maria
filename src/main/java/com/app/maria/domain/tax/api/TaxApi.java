package com.app.maria.domain.tax.api;

import com.app.maria.domain.tax.dto.TaxBatchHistoryDTO;
import com.app.maria.domain.tax.dto.response.TaxCalculationPreviewResponseDTO;
import com.app.maria.domain.tax.dto.response.TaxCalculationSaveResponseDTO;
import com.app.maria.domain.tax.dto.response.TaxSnapshotBatchResultResponseDTO;
import com.app.maria.domain.tax.dto.response.TaxSnapshotResponseDTO;
import com.app.maria.domain.tax.service.TaxCalculationService;
import com.app.maria.global.response.ApiResponseDTO;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/tax")
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasAnyRole('ADMIN', 'SETTLEMENT', 'REVIEWER', 'VIEWER')")
public class TaxApi {
    private final TaxCalculationService taxCalculationService;

    @PreAuthorize("hasAnyRole('ADMIN', 'SETTLEMENT', 'REVIEWER', 'VIEWER')")
    @GetMapping("/preview/{accountId}")
    public ResponseEntity<ApiResponseDTO<TaxCalculationPreviewResponseDTO>> preview(
            @PathVariable @Positive Long accountId) {
        return ResponseEntity.ok(
                ApiResponseDTO.of("세금계산 성공", taxCalculationService.taxCalculate(accountId)));
    }

    @PreAuthorize("hasAnyRole('SETTLEMENT', 'ADMIN')")
    @PostMapping("/calculations/{accountId}")
    public ResponseEntity<ApiResponseDTO<TaxCalculationSaveResponseDTO>> confirm(
            @PathVariable @Positive Long accountId) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "세액 확정 저장 성공", taxCalculationService.calculateAndSave(accountId)));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SETTLEMENT', 'REVIEWER', 'VIEWER')")
    @GetMapping("/snapshots")
    public ResponseEntity<ApiResponseDTO<List<TaxSnapshotResponseDTO>>> snapshots(
            @RequestParam List<Long> accountIds) {
        return ResponseEntity.ok(
                ApiResponseDTO.of("세액 스냅샷 조회 성공", taxCalculationService.findSnapshots(accountIds)));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SETTLEMENT')")
    @PostMapping("/snapshots/jobs")
    public ResponseEntity<ApiResponseDTO<TaxSnapshotBatchResultResponseDTO>>
            triggerSnapshotBatch() {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(
                        ApiResponseDTO.of(
                                "세액 스냅샷 배치 실행 요청 완료",
                                taxCalculationService.triggerSnapshotBatch()));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SETTLEMENT', 'REVIEWER', 'VIEWER')")
    @GetMapping("/snapshots/jobs")
    public ResponseEntity<ApiResponseDTO<List<TaxBatchHistoryDTO>>> snapshotBatchHistory() {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "세액 스냅샷 배치 이력 조회 성공", taxCalculationService.getRecentBatchHistory()));
    }
}
