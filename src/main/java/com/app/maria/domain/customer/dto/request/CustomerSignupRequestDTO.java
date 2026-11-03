package com.app.maria.domain.customer.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** 회원가입 요청. 주민번호(rrn)는 ci_hash 계산에만 쓰고 저장하거나 로그에 남기지 않는다. */
@Getter
@Setter
public class CustomerSignupRequestDTO {
    @NotBlank(message = "아이디를 입력해 주세요.")
    @Pattern(regexp = "^[A-Za-z0-9]{4,20}$", message = "아이디는 영문·숫자 4~20자여야 합니다.")
    private String loginId;

    @NotBlank(message = "비밀번호를 입력해 주세요.")
    @Pattern(regexp = PasswordRule.REGEX, message = PasswordRule.MESSAGE)
    private String password;

    @NotBlank(message = "주민번호를 입력해 주세요.")
    @Pattern(regexp = "^\\d{13}$", message = "주민번호는 숫자 13자리여야 합니다.")
    private String rrn;

    @NotBlank(message = "이름을 입력해 주세요.")
    @Size(max = 50, message = "이름은 50자 이하여야 합니다.")
    private String name;

    @NotNull(message = "생년월일을 입력해 주세요.")
    @Past(message = "생년월일은 과거 날짜여야 합니다.")
    private LocalDate birthDate;

    @NotBlank(message = "휴대폰 번호를 입력해 주세요.")
    @Pattern(regexp = ContactRule.PHONE_REGEX, message = ContactRule.PHONE_MESSAGE)
    private String phone;

    @NotBlank(message = "이메일을 입력해 주세요.")
    @Size(max = 100, message = "이메일은 100자 이하여야 합니다.")
    @Pattern(regexp = ContactRule.EMAIL_REGEX, message = ContactRule.EMAIL_MESSAGE)
    private String email;
}
