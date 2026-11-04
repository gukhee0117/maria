package com.app.maria.domain.customer.api;

import com.app.maria.domain.customer.dto.request.CustomerPasswordChangeRequestDTO;
import com.app.maria.domain.customer.dto.request.CustomerProfileUpdateRequestDTO;
import com.app.maria.domain.customer.dto.response.CustomerProfileResponseDTO;
import com.app.maria.domain.customer.service.CustomerProfileService;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customer")
@RequiredArgsConstructor
public class CustomerProfileApi {
    private final CustomerProfileService profileService;

    @GetMapping("/profile")
    @Operation(summary = "내 정보 조회 (인증 필수)")
    public ApiResponseDTO<CustomerProfileResponseDTO> get(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return ApiResponseDTO.of("내 정보 조회 성공", profileService.get(principal.customerId()));
    }

    @PatchMapping("/profile")
    @Operation(summary = "내 정보 수정: 휴대폰·이메일 (인증·CSRF 필수). 보낸 항목만 바꾼다")
    public ApiResponseDTO<CustomerProfileResponseDTO> update(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @Valid @RequestBody CustomerProfileUpdateRequestDTO body) {
        return ApiResponseDTO.of(
                "내 정보를 수정했습니다.", profileService.update(principal.customerId(), body));
    }

    @PostMapping("/auth/password")
    @Operation(summary = "비밀번호 변경 (인증·CSRF 필수). 현재 비밀번호를 확인하고 세션은 유지한다")
    public ApiResponseDTO<Void> changePassword(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @Valid @RequestBody CustomerPasswordChangeRequestDTO body) {
        profileService.changePassword(principal.customerId(), body);
        return ApiResponseDTO.of("비밀번호를 변경했습니다.", null);
    }
}
