package com.app.maria.domain.customer.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 내 정보 수정. 보낸 항목만 바꾼다. 이름·생년월일은 본인확인 값이라 받지 않는다. */
@Getter
@Setter
public class CustomerProfileUpdateRequestDTO {
    @Pattern(regexp = ContactRule.PHONE_REGEX, message = ContactRule.PHONE_MESSAGE)
    private String phone;

    @Size(max = 100, message = "이메일은 100자 이하여야 합니다.")
    @Pattern(regexp = ContactRule.EMAIL_REGEX, message = ContactRule.EMAIL_MESSAGE)
    private String email;
}
