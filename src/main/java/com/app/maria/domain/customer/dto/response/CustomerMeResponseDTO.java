package com.app.maria.domain.customer.dto.response;

import com.app.maria.global.security.customer.CustomerPrincipal;
import java.time.Instant;
import lombok.Getter;

@Getter
public class CustomerMeResponseDTO {
    private final Long customerId;
    private final String loginId;
    private final String name;

    /** 지금 요청을 기준으로 세션이 만료되는 시각. 화면이 남은 시간을 세는 데 쓴다. */
    private final Instant expiresAt;

    public CustomerMeResponseDTO(CustomerPrincipal principal, Instant expiresAt) {
        customerId = principal.customerId();
        loginId = principal.loginId();
        name = principal.name();
        this.expiresAt = expiresAt;
    }
}
