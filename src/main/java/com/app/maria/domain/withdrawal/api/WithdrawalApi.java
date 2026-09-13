package com.app.maria.domain.withdrawal.api;

import com.app.maria.domain.withdrawal.dto.WithdrawalResultDTO;
import com.app.maria.domain.withdrawal.dto.request.WithdrawalRequestDTO;
import com.app.maria.domain.withdrawal.dto.response.WithdrawalDetailResponseDTO;
import com.app.maria.domain.withdrawal.dto.response.WithdrawalListResponseDTO;
import com.app.maria.domain.withdrawal.service.WithdrawalQueryService;
import com.app.maria.domain.withdrawal.service.WithdrawalService;
import com.app.maria.domain.withdrawal.type.WithdrawalStatus;
import com.app.maria.global.response.ApiResponseDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/withdrawals")
@PreAuthorize("hasAnyRole('ADMIN', 'SETTLEMENT', 'REVIEWER', 'VIEWER')")
public class WithdrawalApi {
    private final WithdrawalQueryService withdrawalQueryService;
    private final WithdrawalService withdrawalService;

    @PostMapping
    @PreAuthorize("denyAll()")
    public ResponseEntity<ApiResponseDTO<WithdrawalResultDTO>> withdraw(
            @Valid @RequestBody WithdrawalRequestDTO requestDTO) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseDTO.of("인출 신청 처리 완료", withdrawalService.withdraw(requestDTO)));
    }

    @GetMapping
    public ResponseEntity<ApiResponseDTO<List<WithdrawalListResponseDTO>>> getWithdrawals(
            @RequestParam(required = false) WithdrawalStatus status) {
        return ResponseEntity.ok(
                ApiResponseDTO.of("인출 내역 목록 조회 완료", withdrawalQueryService.getWithdrawals(status)));
    }

    @GetMapping("/{withdrawalId}")
    public ResponseEntity<ApiResponseDTO<WithdrawalDetailResponseDTO>> getWithdrawal(
            @PathVariable @Positive(message = "인출 ID는 0보다 커야 합니다.") Long withdrawalId) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "인출 내역 상세 조회 완료", withdrawalQueryService.getWithdrawal(withdrawalId)));
    }

    @GetMapping("/accounts/{accountId}")
    public ResponseEntity<ApiResponseDTO<List<WithdrawalListResponseDTO>>>
            getWithdrawalsByAccountId(
                    @PathVariable @Positive(message = "계좌 ID는 0보다 커야 합니다.") Long accountId) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "계좌별 인출 내역 조회 완료",
                        withdrawalQueryService.getWithdrawalsByAccountId(accountId)));
    }
}
