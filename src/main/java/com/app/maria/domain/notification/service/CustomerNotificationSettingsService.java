package com.app.maria.domain.notification.service;

import com.app.maria.domain.notification.dto.CustomerNotificationSettingsRequestDTO;
import com.app.maria.domain.notification.dto.CustomerNotificationSettingsResponseDTO;

public interface CustomerNotificationSettingsService {
    CustomerNotificationSettingsResponseDTO get(Long customerId);

    CustomerNotificationSettingsResponseDTO update(
            Long customerId, CustomerNotificationSettingsRequestDTO request);
}
