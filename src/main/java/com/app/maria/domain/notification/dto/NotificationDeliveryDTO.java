package com.app.maria.domain.notification.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class NotificationDeliveryDTO {
    private String email;
    private boolean pushEnabled;
    private boolean emailEnabled;
    private boolean kindEnabled;
}
