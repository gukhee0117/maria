package com.app.maria.domain.customer.api;

import com.app.maria.domain.customer.dto.request.CustomerWithdrawalRequestDTO;
import com.app.maria.domain.customer.service.CustomerWithdrawalService;
import com.app.maria.domain.withdrawal.dto.WithdrawalResultDTO;
import com.app.maria.domain.withdrawal.dto.response.WithdrawalDetailResponseDTO;
import com.app.maria.domain.withdrawal.dto.response.WithdrawalListResponseDTO;
import com.app.maria.global.client.generalaccount.dto.response.GeneralAccountResponseDTO;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/customer")
public class CustomerWithdrawalApi {

    private final CustomerWithdrawalService customerWithdrawalService;

    @GetMapping("/general-accounts")
    public ResponseEntity<ApiResponseDTO<List<GeneralAccountResponseDTO>>> getDestinationAccounts(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "인출 목적지 계좌 목록 조회 성공",
                        customerWithdrawalService.getDestinationAccounts(principal.customerId())));
    }

    @PostMapping("/withdrawals")
    public ResponseEntity<ApiResponseDTO<WithdrawalResultDTO>> withdraw(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @Valid @RequestBody CustomerWithdrawalRequestDTO request) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "인출 신청 완료",
                        customerWithdrawalService.withdraw(principal.customerId(), request)));
    }

    @GetMapping("/withdrawals")
    public ResponseEntity<ApiResponseDTO<List<WithdrawalListResponseDTO>>> getWithdrawals(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "인출 이력 조회 성공",
                        customerWithdrawalService.getWithdrawals(principal.customerId())));
    }

    @GetMapping("/withdrawals/{withdrawalId}")
    public ResponseEntity<ApiResponseDTO<WithdrawalDetailResponseDTO>> getWithdrawal(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable @Positive(message = "인출 ID는 0보다 커야 합니다.") Long withdrawalId) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "인출 상세 조회 성공",
                        customerWithdrawalService.getWithdrawal(
                                principal.customerId(), withdrawalId)));
    }
}
