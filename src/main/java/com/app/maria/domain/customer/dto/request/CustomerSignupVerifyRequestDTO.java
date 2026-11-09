package com.app.maria.domain.customer.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CustomerSignupVerifyRequestDTO(@NotBlank @Pattern(regexp = "[0-9]{6}") String code) {}
