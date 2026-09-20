package com.app.maria.domain.account.api;

import com.app.maria.domain.account.dto.request.*;
import com.app.maria.domain.account.dto.response.AccountJoinResponseDTO;
import com.app.maria.domain.account.dto.response.AccountLimitUsageResponseDTO;
import com.app.maria.domain.account.dto.response.AccountLogResponseDTO;
import com.app.maria.domain.account.dto.response.AccountManagementDetailResponseDTO;
import com.app.maria.domain.account.dto.response.AccountResponseDTO;
import com.app.maria.domain.account.service.AccountService;
import com.app.maria.global.response.ApiResponseDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/account")
@PreAuthorize("hasAnyRole('ADMIN', 'SETTLEMENT', 'REVIEWER', 'VIEWER')")
public class AccountApi {

    private final AccountService accountService;

    @GetMapping("/list")
    public ResponseEntity<ApiResponseDTO<List<AccountJoinResponseDTO>>> getAccountList() {
        return ResponseEntity.ok(ApiResponseDTO.of("계좌 정보 전체 조회", accountService.findAll()));
    }

    @GetMapping("/requiring-action-count")
    public ResponseEntity<ApiResponseDTO<Integer>> getAccountsRequiringActionCount() {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "처리 필요 계좌 건수 조회", accountService.getAccountsRequiringActionCount()));
    }

    @GetMapping("/available-limit")
    public ResponseEntity<ApiResponseDTO<BigDecimal>> getAvailableLimit(
            @RequestParam @Positive(message = "사용자 ID는 0보다 커야 합니다.") Long customerId) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "RIA 설정 가능 최대 한도 조회", accountService.getAvailableLimit(customerId)));
    }

    @PreAuthorize("hasRole('REVIEWER')")
    @PostMapping("/applications")
    public ResponseEntity<ApiResponseDTO<AccountResponseDTO>> apply(
            @Valid @RequestBody AccountRequestDTO requestDTO) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseDTO.of("계좌 개설 신청 처리 완료", accountService.applyAccount(requestDTO)));
    }

    @PostMapping("/{accountId}/approve")
    @PreAuthorize("hasRole('REVIEWER')")
    public ResponseEntity<ApiResponseDTO<AccountResponseDTO>> approve(
            @PathVariable @Positive(message = "계좌 ID는 0보다 커야 합니다.") Long accountId) {
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponseDTO.of("계좌 승인", accountService.approveAccount(accountId)));
    }

    @PostMapping("/{accountId}/reject")
    @PreAuthorize("hasRole('REVIEWER')")
    public ResponseEntity<ApiResponseDTO<AccountResponseDTO>> reject(
            @PathVariable @Positive(message = "계좌 ID는 0보다 커야 합니다.") Long accountId,
            @Valid @RequestBody ReasonRequestDTO reasonRequestDTO) {
        return ResponseEntity.status(HttpStatus.OK)
                .body(
                        ApiResponseDTO.of(
                                "계좌 반려",
                                accountService.rejectAccount(
                                        accountId, reasonRequestDTO.getReason())));
    }

    @PreAuthorize("hasRole('REVIEWER')")
    @PostMapping("/{accountId}/reapply")
    public ResponseEntity<ApiResponseDTO<AccountResponseDTO>> reapply(
            @PathVariable @Positive(message = "계좌 ID는 0보다 커야 합니다.") Long accountId,
            @Valid @RequestBody AccountReapplyRequestDTO accountRequestDTO) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "계좌 재신청",
                        accountService.reapplyAccountByAccountId(accountId, accountRequestDTO)));
    }

    @GetMapping("/{accountId}")
    public ResponseEntity<ApiResponseDTO<AccountResponseDTO>> getAccount(
            @PathVariable @Positive(message = "계좌 ID는 0보다 커야 합니다.") Long accountId) {
        return ResponseEntity.ok(
                ApiResponseDTO.of("계좌 조회", accountService.getAccountByAccountId(accountId)));
    }

    @GetMapping("/{accountId}/status-logs")
    public ResponseEntity<ApiResponseDTO<List<AccountLogResponseDTO>>> getStatusLogs(
            @PathVariable @Positive(message = "계좌 ID는 0보다 커야 합니다.") Long accountId) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "계좌 상태 이력 조회", accountService.getStatusLogsByAccountId(accountId)));
    }

    @GetMapping("/{accountId}/management-detail")
    public ResponseEntity<ApiResponseDTO<AccountManagementDetailResponseDTO>> getManagementDetail(
            @PathVariable @Positive(message = "계좌 ID는 0보다 커야 합니다.") Long accountId) {
        return ResponseEntity.ok(
                ApiResponseDTO.of("계좌 관리 상세 조회", accountService.getManagementDetail(accountId)));
    }

    @PostMapping("/{accountId}/override")
    @PreAuthorize("hasRole('REVIEWER')")
    public ResponseEntity<ApiResponseDTO<AccountResponseDTO>> override(
            @PathVariable @Positive(message = "계좌 ID는 0보다 커야 합니다.") Long accountId,
            @Valid @RequestBody ReasonRequestDTO reasonRequestDTO) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "계좌 상태 오버라이드",
                        accountService.overrideAccount(accountId, reasonRequestDTO.getReason())));
    }

    @PutMapping("/update/limit")
    @PreAuthorize("hasRole('REVIEWER')")
    public ResponseEntity<ApiResponseDTO<AccountResponseDTO>> updateLimit(
            @Valid @RequestBody AccountLimitUpdateRequestDTO requestDTO) {
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponseDTO.of("계좌 한도 변경", accountService.updateAccountLimit(requestDTO)));
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponseDTO<List<AccountLimitUsageResponseDTO>>> searchAccounts(
            @Valid @ModelAttribute AccountSearchRequestDTO requestDTO) {
        return ResponseEntity.ok(
                ApiResponseDTO.of("계좌 검색", accountService.searchAccounts(requestDTO)));
    }
}
