package com.app.maria.domain.notification.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class CustomerNotificationConsentDTO {
    private String consentType;
    private boolean agreed;
}
