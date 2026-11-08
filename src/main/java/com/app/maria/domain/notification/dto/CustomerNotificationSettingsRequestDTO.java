package com.app.maria.domain.notification.dto;

import jakarta.validation.constraints.NotNull;

public record CustomerNotificationSettingsRequestDTO(
        @NotNull Boolean push,
        @NotNull Boolean sms,
        @NotNull Boolean email,
        @NotNull Boolean settle,
        @NotNull Boolean band,
        // Retired fields are accepted only for compatibility and ignored.
        Boolean reply,
        Boolean kiru) {}
