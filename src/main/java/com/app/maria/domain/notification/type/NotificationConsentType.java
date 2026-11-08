package com.app.maria.domain.notification.type;

import com.app.maria.domain.notification.dto.CustomerNotificationSettingsRequestDTO;
import java.util.function.Function;

public enum NotificationConsentType {
    NOTIFY_PUSH(true, CustomerNotificationSettingsRequestDTO::push),
    NOTIFY_SMS(false, CustomerNotificationSettingsRequestDTO::sms),
    NOTIFY_EMAIL(false, CustomerNotificationSettingsRequestDTO::email),
    NOTIFY_SETTLEMENT(true, CustomerNotificationSettingsRequestDTO::settle),
    NOTIFY_BAND(true, CustomerNotificationSettingsRequestDTO::band),
    NOTIFY_COMMENT(true, CustomerNotificationSettingsRequestDTO::reply),
    NOTIFY_KIRU(true, CustomerNotificationSettingsRequestDTO::kiru);

    private final boolean defaultValue;
    private final Function<CustomerNotificationSettingsRequestDTO, Boolean> requestedValue;

    NotificationConsentType(
            boolean defaultValue,
            Function<CustomerNotificationSettingsRequestDTO, Boolean> requestedValue) {
        this.defaultValue = defaultValue;
        this.requestedValue = requestedValue;
    }

    public boolean defaultValue() {
        return defaultValue;
    }

    public boolean requestedValue(CustomerNotificationSettingsRequestDTO request) {
        return requestedValue.apply(request);
    }
}
