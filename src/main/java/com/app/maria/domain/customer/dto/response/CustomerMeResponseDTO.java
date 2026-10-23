package com.app.maria.domain.customer.dto.response;

import com.app.maria.global.security.customer.CustomerPrincipal;
import lombok.Getter;

@Getter
public class CustomerMeResponseDTO {
    private final Long customerId;
    private final String loginId;
    private final String name;

    public CustomerMeResponseDTO(CustomerPrincipal principal) {
        customerId = principal.customerId();
        loginId = principal.loginId();
        name = principal.name();
    }
}
