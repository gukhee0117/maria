package com.app.maria.domain.notification.api;

import com.app.maria.domain.notification.dto.CustomerNotificationResponseDTO;
import com.app.maria.domain.notification.dto.NotificationReadRequest;
import com.app.maria.domain.notification.service.CustomerNotificationService;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/customer/notifications")
public class CustomerNotificationApi {

    private final CustomerNotificationService notificationService;

    @GetMapping
    public ApiResponseDTO<List<CustomerNotificationResponseDTO>> list(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return ApiResponseDTO.of("알림을 조회했습니다.", notificationService.list(principal.customerId()));
    }

    @PatchMapping("/read")
    public ApiResponseDTO<Void> markVisibleRead(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @Valid @RequestBody NotificationReadRequest body) {
        notificationService.markVisibleRead(principal.customerId(), body.notificationIds());
        return ApiResponseDTO.of("표시된 알림을 읽음 처리했습니다.", null);
    }

    @PatchMapping("/read-all")
    public ApiResponseDTO<Void> markAllRead(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        notificationService.markAllRead(principal.customerId());
        return ApiResponseDTO.of("알림을 모두 읽음 처리했습니다.", null);
    }
}
