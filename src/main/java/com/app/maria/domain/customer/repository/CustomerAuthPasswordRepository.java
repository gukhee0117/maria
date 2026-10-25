package com.app.maria.domain.customer.repository;

import com.app.maria.domain.customer.entity.CustomerAuthPasswordEntity;
import org.springframework.data.repository.Repository;

public interface CustomerAuthPasswordRepository
        extends Repository<CustomerAuthPasswordEntity, Long> {
    CustomerAuthPasswordEntity saveAndFlush(CustomerAuthPasswordEntity password);
}
