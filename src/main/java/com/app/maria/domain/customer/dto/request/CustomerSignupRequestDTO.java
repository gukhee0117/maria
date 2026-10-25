package com.app.maria.domain.customer.dto.request;

import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** 회원가입 요청. 주민번호(rrn)는 ci_hash 계산에만 쓰고 저장하거나 로그에 남기지 않는다. */
@Getter
@Setter
public class CustomerSignupRequestDTO {
    private String loginId;
    private String password;
    private String rrn;
    private String name;
    private LocalDate birthDate;
}
