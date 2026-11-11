package com.app.maria.domain.notification.type;

public enum NotificationConsentType {
    NOTIFY_PUSH(true),
    NOTIFY_SMS(false),
    NOTIFY_EMAIL(true),
    NOTIFY_SETTLEMENT(true),
    NOTIFY_BAND(true),
    NOTIFY_COMMENT(true),
    NOTIFY_KIRU(true);

    private final boolean defaultValue;

    NotificationConsentType(boolean defaultValue) {
        this.defaultValue = defaultValue;
    }

    public boolean defaultValue() {
        return defaultValue;
    }
}
