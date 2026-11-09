package com.app.maria.domain.account.api;

import com.app.maria.domain.account.dto.request.CustomerAccountApplyRequestDTO;
import com.app.maria.domain.account.dto.request.MydataConsentRequestDTO;
import com.app.maria.domain.account.dto.response.AccountResponseDTO;
import com.app.maria.domain.account.service.AccountService;
import com.app.maria.domain.account.service.MydataConsentService;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/customer/account")
public class CustomerAccountApi {
    private final AccountService accountService;
    private final MydataConsentService mydataConsentService;

    @GetMapping("/available-limit")
    public ResponseEntity<ApiResponseDTO<BigDecimal>> getAvailableLimit(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "RIA 설정 가능 최대 한도 조회",
                        accountService.getCustomerAvailableLimit(principal.customerId())));
    }

    @PostMapping("/applications")
    public ResponseEntity<ApiResponseDTO<AccountResponseDTO>> apply(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @Valid @RequestBody CustomerAccountApplyRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        ApiResponseDTO.of(
                                "계좌 개설 신청 처리 완료",
                                accountService.applyCustomerAccount(
                                        principal.customerId(),
                                        request.getLimitAmount(),
                                        request.isMydataConsent())));
    }

    @GetMapping("/mydata-consent")
    public ResponseEntity<ApiResponseDTO<Boolean>> getMydataConsent(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "마이데이터 조회 동의 여부", mydataConsentService.isAgreed(principal.customerId())));
    }

    @PutMapping("/mydata-consent")
    public ResponseEntity<ApiResponseDTO<Boolean>> putMydataConsent(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @Valid @RequestBody MydataConsentRequestDTO request) {
        mydataConsentService.record(principal.customerId(), request.agreed());
        return ResponseEntity.ok(ApiResponseDTO.of("마이데이터 조회 동의 저장", request.agreed()));
    }
}
