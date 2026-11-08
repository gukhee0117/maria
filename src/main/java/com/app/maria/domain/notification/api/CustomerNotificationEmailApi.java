package com.app.maria.domain.notification.api;

import com.app.maria.domain.notification.service.NotificationEmailVerificationService;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/customer/notification-email/verification")
public class CustomerNotificationEmailApi {
    private final NotificationEmailVerificationService verification;

    public record Code(@NotBlank @Pattern(regexp = "[0-9]{6}") String code) {}

    @GetMapping
    public ApiResponseDTO<NotificationEmailVerificationService.Status> status(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return ApiResponseDTO.of("이메일 확인 상태", verification.status(principal.customerId()));
    }

    @PostMapping
    public ApiResponseDTO<NotificationEmailVerificationService.Challenge> send(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return ApiResponseDTO.of(
                "등록된 이메일로 인증번호를 보냈습니다.", verification.send(principal.customerId()));
    }

    @PostMapping("/confirm")
    public ApiResponseDTO<NotificationEmailVerificationService.Status> confirm(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @Valid @RequestBody Code body) {
        return ApiResponseDTO.of(
                "이메일 수신 주소를 확인했습니다.", verification.confirm(principal.customerId(), body.code()));
    }
}
