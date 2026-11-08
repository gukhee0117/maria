package com.app.maria.domain.notification.api;

import com.app.maria.domain.notification.dto.CustomerNotificationSettingsRequestDTO;
import com.app.maria.domain.notification.dto.CustomerNotificationSettingsResponseDTO;
import com.app.maria.domain.notification.service.CustomerNotificationSettingsService;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/customer/notification-settings")
public class CustomerNotificationSettingsApi {

    private final CustomerNotificationSettingsService settingsService;

    @GetMapping
    public ApiResponseDTO<CustomerNotificationSettingsResponseDTO> get(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return ApiResponseDTO.of("알림 설정을 조회했습니다.", settingsService.get(principal.customerId()));
    }

    @PutMapping
    public ApiResponseDTO<CustomerNotificationSettingsResponseDTO> update(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @Valid @RequestBody CustomerNotificationSettingsRequestDTO request) {
        return ApiResponseDTO.of(
                "알림 설정을 저장했습니다.", settingsService.update(principal.customerId(), request));
    }
}
