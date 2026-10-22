package com.app.maria.domain.account.repository;

import com.app.maria.domain.account.entity.AccountEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 고객 ID는 요청 본문이 아닌 인증된 CustomerPrincipal에서 전달한다. */
public interface AccountRepository extends Repository<AccountEntity, Long> {
    @EntityGraph(attributePaths = "customer")
    Optional<AccountEntity> findByCustomer_CustomerId(Long customerId);

    /** 입고 신청 등 쓰기 경로에서 동시 요청을 직렬화한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AccountEntity a join fetch a.customer c where c.customerId = :customerId")
    Optional<AccountEntity> findByCustomer_CustomerIdForUpdate(@Param("customerId") Long customerId);
}
