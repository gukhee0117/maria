package com.app.maria.domain.customer.dto.request;

/** 가입과 비밀번호 변경이 같이 쓰는 비밀번호 규칙. 화면의 가입 폼 규칙과 같다. */
public final class PasswordRule {
    // BCrypt는 72바이트까지만 처리하므로 글자 수와 바이트 수가 같은 ASCII 출력 문자만 허용한다.
    public static final String REGEX =
            "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z0-9])[\\x21-\\x7E]{8,72}$";
    public static final String MESSAGE = "비밀번호는 공백 없는 8~72자이며 영문·숫자·특수문자를 모두 포함해야 합니다.";

    private PasswordRule() {}
}
