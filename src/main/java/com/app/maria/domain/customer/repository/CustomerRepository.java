package com.app.maria.domain.customer.repository;

import com.app.maria.domain.customer.entity.CustomerEntity;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/** 가입 조회·저장 전용. */
public interface CustomerRepository extends Repository<CustomerEntity, Long> {
    Optional<CustomerEntity> findByCiHash(String ciHash);

    CustomerEntity saveAndFlush(CustomerEntity customer);
}
