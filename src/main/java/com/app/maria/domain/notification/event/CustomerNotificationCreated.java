package com.app.maria.domain.notification.event;

import com.app.maria.domain.notification.dto.SettlementEmailDetails;

public record CustomerNotificationCreated(
        Long customerId,
        String title,
        String content,
        boolean pushEnabled,
        String email,
        SettlementEmailDetails settlement) {
    public CustomerNotificationCreated(
            Long customerId, String title, String content, boolean pushEnabled, String email) {
        this(customerId, title, content, pushEnabled, email, null);
    }
}
