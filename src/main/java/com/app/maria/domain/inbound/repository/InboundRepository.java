package com.app.maria.domain.inbound.repository;

import com.app.maria.domain.inbound.entity.InboundEntity;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.Repository;

/** 고객 ID는 서비스가 인증 컨텍스트에서 확인해 전달한다. 저장 전체는 서비스의 JPA 트랜잭션으로 묶는다. */
public interface InboundRepository extends Repository<InboundEntity, Long> {
    InboundEntity save(InboundEntity inbound);

    @EntityGraph(attributePaths = {"account", "account.customer"})
    Optional<InboundEntity> findByInboundIdAndAccount_Customer_CustomerId(
            Long inboundId, Long customerId);

    @EntityGraph(attributePaths = {"account", "account.customer"})
    Page<InboundEntity> findByAccount_Customer_CustomerIdOrderByProcessedAtDescInboundIdDesc(
            Long customerId, Pageable pageable);
}
