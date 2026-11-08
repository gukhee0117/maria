package com.app.maria.domain.notification.dto;

import com.app.maria.domain.notification.type.NotificationConsentType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class CustomerNotificationConsentDTO {
    private NotificationConsentType consentType;
    private boolean agreed;
}
