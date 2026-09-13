package com.app.maria.domain.settlement.api;

import com.app.maria.domain.settlement.dto.KrwExchangeDTO;
import com.app.maria.domain.settlement.dto.SettlementBatchDTO;
import com.app.maria.domain.settlement.dto.SettlementItemDTO;
import com.app.maria.domain.settlement.dto.SettlementJoinDTO;
import com.app.maria.domain.settlement.service.SettlementService;
import com.app.maria.domain.settlement.type.BatchStatus;
import com.app.maria.global.response.ApiResponseDTO;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
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

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/settlement")
@PreAuthorize("hasAnyRole('ADMIN', 'SETTLEMENT', 'REVIEWER', 'VIEWER')")
public class SettlementApi {

    private final SettlementService settlementService;

    @PostMapping("/jobs")
    @PreAuthorize("hasAnyRole('ADMIN', 'SETTLEMENT')")
    // Batch 실행 요청
    public ResponseEntity<ApiResponseDTO<SettlementBatchDTO>> executeSettlementBatch() {
        SettlementBatchDTO batch = settlementService.executeSettlementBatchByAdmin();
        HttpStatus status =
                batch.getStatus() == BatchStatus.RUNNING ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponseDTO.of("확정산 배치 실행 요청 완료", batch));
    }

    @GetMapping("/batches")
    // Batch 목록
    public ResponseEntity<ApiResponseDTO<List<SettlementBatchDTO>>> getSettlementBatches() {
        return ResponseEntity.ok(
                ApiResponseDTO.of("확정산 배치 목록 조회", settlementService.getSettlementBatches()));
    }

    @GetMapping("/batches/{batchId}")
    // Batch 단건 -> @Positive ==> 필드 값이 0보다 큰 양수인지 검사
    public ResponseEntity<ApiResponseDTO<SettlementBatchDTO>> getSettlementBatch(
            @PathVariable @Positive Long batchId) {
        return ResponseEntity.ok(
                ApiResponseDTO.of("확정산 배치 단건 조회", settlementService.getSettlementBatch(batchId)));
    }

    @GetMapping("/batches/detail/{batchId}")
    // Batch 건별 상세
    public ResponseEntity<ApiResponseDTO<List<SettlementJoinDTO>>> getSettlementBatchDetail(
            @PathVariable @Positive Long batchId) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "확정산 배치 단건 상세 조회", settlementService.getSettlementBatchDetail(batchId)));
    }

    @GetMapping("/batches/detail/fail/{batchId}")
    // Batch 실패 건 상세
    public ResponseEntity<ApiResponseDTO<List<SettlementJoinDTO>>> getSettlementBatchFailDetail(
            @PathVariable @Positive Long batchId) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "확정산 배치 단건 조회", settlementService.getSettlementBatchFailDetail(batchId)));
    }

    @GetMapping("/batches/run/{runId}")
    // runId 조회
    public ResponseEntity<ApiResponseDTO<SettlementBatchDTO>> getSettlementBatchByRunId(
            @PathVariable @NotBlank(message = "runId는 필수입니다.") String runId) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "확정산 배치 실행 ID 조회", settlementService.getSettlementBatchByRunId(runId)));
    }

    @GetMapping("/batches/{batchId}/items/pending")
    // 대기 Item 조회
    public ResponseEntity<ApiResponseDTO<List<SettlementItemDTO>>> getPendingSettlementItems(
            @PathVariable @Positive Long batchId,
            @RequestParam(defaultValue = "0") @PositiveOrZero Long lastItemId) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "확정산 대기 항목 조회",
                        settlementService.getPendingSettlementItems(batchId, lastItemId)));
    }

    @GetMapping("/batches/{batchId}/items/{itemId}")
    // Item 조인 상세
    public ResponseEntity<ApiResponseDTO<SettlementJoinDTO>> getSettlementItem(
            @PathVariable @Positive Long batchId, @PathVariable @Positive Long itemId) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "확정산 항목 상세 조회", settlementService.getSettlementItem(batchId, itemId)));
    }

    @PostMapping("/batches/{batchId}/items/{itemId}/retry")
    @PreAuthorize("hasAnyRole('ADMIN', 'SETTLEMENT')")
    public ResponseEntity<ApiResponseDTO<SettlementItemDTO>> retryFailedSettlementItem(
            @PathVariable @Positive Long batchId, @PathVariable @Positive Long itemId) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "실패한 확정산 항목 재처리 시도 완료",
                        settlementService.retryFailedSettlementItem(batchId, itemId)));
    }

    @PostMapping("/batches/{batchId}/retry")
    @PreAuthorize("hasAnyRole('ADMIN', 'SETTLEMENT')")
    public ResponseEntity<ApiResponseDTO<SettlementBatchDTO>> retryFailedSettlementBatch(
            @PathVariable @Positive Long batchId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(
                        ApiResponseDTO.of(
                                "실패한 확정산 Batch 재처리 요청 완료",
                                settlementService.retryFailedSettlementBatch(batchId)));
    }

    @GetMapping("/exchanges/{exchangeId}")
    // 환전 상세
    public ResponseEntity<ApiResponseDTO<KrwExchangeDTO>> getKrwExchange(
            @PathVariable @Positive Long exchangeId) {
        return ResponseEntity.ok(
                ApiResponseDTO.of("원화 환전 상세 조회", settlementService.getKrwExchange(exchangeId)));
    }
}
