package com.app.maria.domain.account.repository;

import com.app.maria.domain.account.entity.AccountEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.Repository;

/** 고객 ID는 요청 본문이 아닌 인증된 CustomerPrincipal에서 전달한다. */
public interface AccountRepository extends Repository<AccountEntity, Long> {
    @EntityGraph(attributePaths = "customer")
    Optional<AccountEntity> findByCustomer_CustomerId(Long customerId);
}
