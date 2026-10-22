package com.app.maria.domain.inbound.repository;

import com.app.maria.domain.inbound.entity.InboundMinEntity;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface InboundMinRepository extends Repository<InboundMinEntity, Long> {
    InboundMinEntity save(InboundMinEntity min);

    @Query(
            """
            select evidence
            from InboundMinEntity evidence
            join fetch evidence.inboundDetail detail
            where detail.inbound.inboundId = :inboundId
              and detail.inbound.account.customer.customerId = :customerId
            order by evidence.inboundMinId asc
            """)
    List<InboundMinEntity> findByInboundAndCustomer(
            @Param("inboundId") Long inboundId, @Param("customerId") Long customerId);
}
