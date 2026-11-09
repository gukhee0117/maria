package com.app.maria.domain.notification.dto;

public record CustomerNotificationSettingsResponseDTO(
        boolean push,
        boolean sms,
        boolean email,
        boolean settle,
        boolean band,
        boolean reply,
        boolean kiru) {}
