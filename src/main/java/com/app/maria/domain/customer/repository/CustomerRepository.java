package com.app.maria.domain.customer.repository;

import com.app.maria.domain.customer.entity.CustomerEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 가입 조회·저장 전용. */
public interface CustomerRepository extends Repository<CustomerEntity, Long> {
    /** 같은 고객에게 동시에 가입하면 둘 다 "계정 없음"을 보고 계정이 두 개 생길 수 있어서, 조회와 동시에 행을 잠가 뒤 요청이 앞 요청의 커밋을 기다리게 한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CustomerEntity c where c.ciHash = :ciHash")
    Optional<CustomerEntity> findByCiHashForUpdate(@Param("ciHash") String ciHash);

    CustomerEntity saveAndFlush(CustomerEntity customer);
}
