package com.app.maria.domain.customer.dto.response;

import java.time.Instant;

public record CustomerSignupChallengeDTO(
        String maskedEmail, Instant expiresAt, Instant resendAvailableAt) {}
