package com.app.maria.global.security.customer;

import java.io.Serializable;

/** 관리자 Long principal과 구분되는 고객 세션 식별자. 비밀번호·CI는 저장하지 않는다. */
public record CustomerPrincipal(Long customerId, String loginId, String name)
        implements Serializable {}
