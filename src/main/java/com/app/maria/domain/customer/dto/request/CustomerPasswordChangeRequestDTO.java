package com.app.maria.domain.customer.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CustomerPasswordChangeRequestDTO {
    @NotBlank(message = "현재 비밀번호를 입력해 주세요.")
    @Size(max = 72, message = "비밀번호는 72자 이하여야 합니다.")
    private String currentPassword;

    @NotBlank(message = "새 비밀번호를 입력해 주세요.")
    @Pattern(regexp = PasswordRule.REGEX, message = PasswordRule.MESSAGE)
    private String newPassword;
}
