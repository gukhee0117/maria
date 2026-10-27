package com.app.maria.domain.chat.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class MessageDTO {

    @NotBlank
    @Pattern(regexp = "user|assistant", message = "role은 user 또는 assistant여야 합니다.")
    private String role;

    @NotBlank private String content;
}
