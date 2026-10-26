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

    // BCrypt는 72바이트까지만 처리하므로 글자 수와 바이트 수가 같은 ASCII 출력 문자만 허용한다.
    @NotBlank(message = "비밀번호를 입력해 주세요.")
    @Pattern(regexp = "^[\\x21-\\x7E]{8,72}$", message = "비밀번호는 공백 없는 영문·숫자·특수문자 8~72자여야 합니다.")
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
}
