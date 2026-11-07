package com.app.maria.domain.notification.dto;

import jakarta.validation.constraints.NotNull;

public record CustomerNotificationSettingsRequestDTO(
        @NotNull Boolean push,
        @NotNull Boolean sms,
        @NotNull Boolean email,
        @NotNull Boolean settle,
        @NotNull Boolean band,
        @NotNull Boolean reply,
        @NotNull Boolean kiru) {}
