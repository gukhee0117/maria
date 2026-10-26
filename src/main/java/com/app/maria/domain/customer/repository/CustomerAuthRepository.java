package com.app.maria.domain.customer.repository;

import com.app.maria.domain.customer.entity.CustomerAuthEntity;
import com.app.maria.domain.customer.entity.CustomerAuthEntity.AuthType;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.Repository;

/** 로그인 조회와 가입 저장. 중복 LOCAL 계정은 서비스에서 거절하고, 가입 동시성은 DB UNIQUE(type, login_id)가 막는다. */
public interface CustomerAuthRepository extends Repository<CustomerAuthEntity, Long> {
    @EntityGraph(attributePaths = {"customer", "password"})
    List<CustomerAuthEntity> findTop2ByLoginIdAndTypeOrderByAuthIdAsc(
            String loginId, AuthType type);

    boolean existsByCustomerCustomerId(Long customerId);

    boolean existsByLoginIdAndType(String loginId, AuthType type);

    CustomerAuthEntity saveAndFlush(CustomerAuthEntity auth);
}
