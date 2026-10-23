package com.app.maria.domain.customer.service;

import com.app.maria.domain.customer.dto.request.CustomerLoginRequestDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;

public interface CustomerAuthService {
    CustomerPrincipal authenticate(CustomerLoginRequestDTO request);
}
