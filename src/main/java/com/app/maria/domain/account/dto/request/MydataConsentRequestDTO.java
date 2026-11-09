package com.app.maria.domain.account.dto.request;

import jakarta.validation.constraints.NotNull;

public record MydataConsentRequestDTO(@NotNull Boolean agreed) {}
