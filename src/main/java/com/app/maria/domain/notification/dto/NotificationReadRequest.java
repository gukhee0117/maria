package com.app.maria.domain.notification.dto;

import jakarta.validation.constraints.*;
import java.util.List;

public record NotificationReadRequest(
        @NotNull @Size(min = 1, max = 50) List<@NotNull @Positive Long> notificationIds) {}
