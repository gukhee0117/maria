package com.app.maria.domain.customer.service;

import com.app.maria.domain.customer.dto.request.CustomerLoginRequestDTO;
import com.app.maria.domain.customer.dto.request.CustomerSignupRequestDTO;
import com.app.maria.domain.customer.entity.CustomerAuthEntity;
import com.app.maria.domain.customer.entity.CustomerAuthEntity.AuthType;
import com.app.maria.domain.customer.entity.CustomerAuthPasswordEntity;
import com.app.maria.domain.customer.entity.CustomerEntity;
import com.app.maria.domain.customer.repository.CustomerAuthPasswordRepository;
import com.app.maria.domain.customer.repository.CustomerAuthRepository;
import com.app.maria.domain.customer.repository.CustomerRepository;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.app.maria.global.security.customer.CustomerPrincipal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomerAuthServiceImpl implements CustomerAuthService {
    private final CustomerAuthRepository customerAuthRepository;
    private final CustomerRepository customerRepository;
    private final CustomerAuthPasswordRepository customerAuthPasswordRepository;
    private final PasswordEncoder passwordEncoder;
    private final CiHasher ciHasher;
    private final Clock clock;

    @Override
    @Transactional(transactionManager = "jpaTransactionManager")
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
        credential.markLoggedIn(LocalDateTime.now(clock));
        CustomerEntity customer = credential.getCustomer();
        return new CustomerPrincipal(
                customer.getCustomerId(), credential.getLoginId(), customer.getName());
    }

    @Override
    @Transactional(transactionManager = "jpaTransactionManager")
    public CustomerPrincipal signup(CustomerSignupRequestDTO request) {
        if (customerAuthRepository.existsByLoginIdAndType(request.getLoginId(), AuthType.LOCAL)) {
            throw new AppException(ErrorType.CUSTOMER_LOGIN_ID_DUPLICATED);
        }
        String ciHash = ciHasher.of(request.getRrn());
        try {
            CustomerEntity customer =
                    customerRepository
                            .findByCiHash(ciHash)
                            .map(existing -> verifyUnregistered(existing, request))
                            .orElseGet(
                                    () ->
                                            customerRepository.saveAndFlush(
                                                    CustomerEntity.newUnassigned(
                                                            request.getName(),
                                                            request.getBirthDate(),
                                                            ciHash,
                                                            LocalDateTime.now(clock))));
            CustomerAuthEntity auth =
                    customerAuthRepository.saveAndFlush(
                            CustomerAuthEntity.newLocal(customer, request.getLoginId()));
            customerAuthPasswordRepository.saveAndFlush(
                    CustomerAuthPasswordEntity.of(
                            auth, passwordEncoder.encode(request.getPassword())));
            return new CustomerPrincipal(
                    customer.getCustomerId(), auth.getLoginId(), customer.getName());
        } catch (DataIntegrityViolationException e) {
            throw new AppException(ErrorType.CUSTOMER_SIGNUP_FAILED);
        }
    }

    // 이름·생년월일이 다르거나 이미 계정이 있으면 같은 실패로 응답한다(가입 여부를 알려주지 않기 위해).
    private CustomerEntity verifyUnregistered(
            CustomerEntity customer, CustomerSignupRequestDTO request) {
        boolean sameIdentity =
                customer.getName().equals(request.getName())
                        && customer.getBirthDate().equals(request.getBirthDate());
        if (!sameIdentity
                || customerAuthRepository.existsByCustomerCustomerId(customer.getCustomerId())) {
            throw new AppException(ErrorType.CUSTOMER_SIGNUP_FAILED);
        }
        return customer;
    }
}
