package com.app.maria.domain.community.service;

import com.app.maria.domain.customer.entity.CustomerEntity;
import com.app.maria.domain.customer.repository.CustomerRepository;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CommunityProfileServiceImpl implements CommunityProfileService {
    private final CustomerRepository customerRepository;

    @Override
    @Transactional(transactionManager = "jpaTransactionManager")
    public boolean changeInvestorTypePublic(Long customerId, boolean investorTypePublic) {
        CustomerEntity customer =
                customerRepository
                        .findByIdForUpdate(customerId)
                        .orElseThrow(
                                () -> new AppException(ErrorType.CUSTOMER_NOT_FOUND, customerId));
        customer.changeInvestorTypePublic(investorTypePublic);
        return customer.isInvestorTypePublic();
    }
}
