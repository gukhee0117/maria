package com.app.maria.domain.customer.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CustomerSignupEmailRequestDTO(
        @NotBlank
                @Size(max = 100)
                @Pattern(regexp = ContactRule.EMAIL_REGEX, message = ContactRule.EMAIL_MESSAGE)
                String email) {}
