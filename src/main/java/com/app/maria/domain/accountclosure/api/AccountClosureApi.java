package com.app.maria.domain.accountclosure.api;

import com.app.maria.domain.account.dto.request.ReasonRequestDTO;
import com.app.maria.domain.accountclosure.dto.request.AccountClosureApplyRequestDTO;
import com.app.maria.domain.accountclosure.dto.response.AccountClosureDetailResponseDTO;
import com.app.maria.domain.accountclosure.dto.response.AccountClosureResponseDTO;
import com.app.maria.domain.accountclosure.service.AccountClosureService;
import com.app.maria.domain.accountclosure.type.AccountClosureStatus;
import com.app.maria.global.response.ApiResponseDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/account-closures")
@PreAuthorize("hasAnyRole('ADMIN', 'SETTLEMENT', 'REVIEWER', 'VIEWER')")
public class AccountClosureApi {
    private final AccountClosureService accountClosureService;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'REVIEWER')")
    public ResponseEntity<ApiResponseDTO<Long>> applyClosure(
            @Valid @RequestBody AccountClosureApplyRequestDTO requestDTO) {
        Long closureRequestId =
                accountClosureService.applyClosure(requestDTO.getCustomerId(), requestDTO);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseDTO.of("계좌 해지 신청 완료", closureRequestId));
    }

    @PostMapping("/{closureRequestId}/reject")
    @PreAuthorize("hasRole('REVIEWER')")
    public ResponseEntity<ApiResponseDTO<Void>> rejectClosure(
            @AuthenticationPrincipal Long adminId,
            @PathVariable @Positive(message = "해지 신청 ID는 0보다 커야합니다.") Long closureRequestId,
            @Valid @RequestBody ReasonRequestDTO requestDTO) {
        accountClosureService.rejectClosure(adminId, closureRequestId, requestDTO.getReason());

        return ResponseEntity.ok(ApiResponseDTO.of("계좌 해지 신청 반려 완료", null));
    }

    @PostMapping("/{closureRequestId}/approve")
    @PreAuthorize("hasRole('REVIEWER')")
    public ResponseEntity<ApiResponseDTO<Void>> approveClosure(
            @AuthenticationPrincipal Long adminId,
            @PathVariable @Positive(message = "해지 신청 ID는 0보다 커야합니다.") Long closureRequestId) {
        accountClosureService.approveClosure(adminId, closureRequestId);
        return ResponseEntity.ok(ApiResponseDTO.of("계좌 해지 신청 승인 완료", null));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'REVIEWER')")
    public ResponseEntity<ApiResponseDTO<List<AccountClosureResponseDTO>>> getClosures(
            @RequestParam(defaultValue = "REQUESTED") AccountClosureStatus status) {

        List<AccountClosureResponseDTO> closures = accountClosureService.getClosures(status);

        return ResponseEntity.ok(ApiResponseDTO.of("계좌 해지 신청 목록 조회 완료", closures));
    }

    @GetMapping("/{closureRequestId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'REVIEWER')")
    public ResponseEntity<ApiResponseDTO<AccountClosureDetailResponseDTO>> getClosure(
            @PathVariable @Positive(message = "해지 신청 ID는 0보다 커야 합니다.") Long closureRequestId) {
        AccountClosureDetailResponseDTO closure =
                accountClosureService.getClosure(closureRequestId);
        return ResponseEntity.ok(ApiResponseDTO.of("계좌 해지 신청 상세 조회 완료", closure));
    }
}
