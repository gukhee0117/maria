package com.app.maria.domain.customer.service;

import com.app.maria.domain.customer.dto.request.CustomerLoginRequestDTO;
import com.app.maria.domain.customer.entity.CustomerAuthEntity;
import com.app.maria.domain.customer.entity.CustomerAuthEntity.AuthType;
import com.app.maria.domain.customer.entity.CustomerEntity;
import com.app.maria.domain.customer.repository.CustomerAuthRepository;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import com.app.maria.global.security.customer.CustomerPrincipal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomerAuthServiceImpl implements CustomerAuthService {
    private final CustomerAuthRepository customerAuthRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional(transactionManager = "jpaTransactionManager", readOnly = true)
    public CustomerPrincipal authenticate(CustomerLoginRequestDTO request) {
        List<CustomerAuthEntity> credentials =
                customerAuthRepository.findTop2ByLoginIdAndTypeOrderByAuthIdAsc(
                        request.getLoginId(), AuthType.LOCAL);
        if (credentials.size() != 1) {
            throw new AppException(ErrorType.CUSTOMER_LOGIN_FAILED);
        }
        CustomerAuthEntity credential = credentials.get(0);
        if (credential.getPassword() == null
                || !passwordEncoder.matches(
                        request.getPassword(), credential.getPassword().getPasswordHash())) {
            throw new AppException(ErrorType.CUSTOMER_LOGIN_FAILED);
        }
        CustomerEntity customer = credential.getCustomer();
        return new CustomerPrincipal(
                customer.getCustomerId(), credential.getLoginId(), customer.getName());
    }
}
