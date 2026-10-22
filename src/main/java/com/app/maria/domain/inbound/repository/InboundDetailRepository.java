package com.app.maria.domain.inbound.repository;

import com.app.maria.domain.inbound.entity.InboundDetailEntity;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface InboundDetailRepository extends Repository<InboundDetailEntity, Long> {
    InboundDetailEntity save(InboundDetailEntity detail);

    @EntityGraph(attributePaths = "foreignProduct")
    List<InboundDetailEntity>
            findByInbound_InboundIdAndInbound_Account_Customer_CustomerIdOrderByInboundDetailIdAsc(
                    Long inboundId, Long customerId);

    @EntityGraph(attributePaths = "foreignProduct")
    List<InboundDetailEntity> findByInbound_InboundIdInOrderByInboundDetailIdAsc(
            Collection<Long> inboundIds);

    // currentQty가 아닌 최초 승인 qty를 합산한다. 원본 ID가 NULL인 기존 상세도 제외하지 않는다.
    @Query(
            """
            select coalesce(sum(detail.qty), 0)
            from InboundDetailEntity detail
            where detail.inbound.account.accountId = :accountId
              and detail.foreignProduct.foreignProductId = :foreignProductId
              and detail.sourceGeneralAccountId = :sourceGeneralAccountId
            """)
    BigDecimal sumApprovedQtyByAccountAndProductAndSourceGeneralAccount(
            @Param("accountId") Long accountId,
            @Param("foreignProductId") Long foreignProductId,
            @Param("sourceGeneralAccountId") Long sourceGeneralAccountId);

    @Query(
            """
            select coalesce(sum(detail.qty), 0)
            from InboundDetailEntity detail
            where detail.inbound.account.accountId = :accountId
              and detail.foreignProduct.foreignProductId = :foreignProductId
            """)
    BigDecimal sumApprovedQtyByAccountAndProduct(
            @Param("accountId") Long accountId, @Param("foreignProductId") Long foreignProductId);

    // FIFO lot 배분용: 동일 registrableStockId에 이미 승인된 수량 합산
    @Query(
            """
            select coalesce(sum(detail.qty), 0)
            from InboundDetailEntity detail
            where detail.inbound.account.accountId = :accountId
              and detail.registrableStockId = :registrableStockId
            """)
    BigDecimal sumApprovedQtyByAccountAndRegistrableStockId(
            @Param("accountId") Long accountId,
            @Param("registrableStockId") Long registrableStockId);

    // registrableStockId=NULL인 기존 이력이 있으면 FIFO 배분 불가 — 확인 대상으로 처리
    @Query(
            """
            select count(detail) > 0
            from InboundDetailEntity detail
            where detail.inbound.account.accountId = :accountId
              and detail.foreignProduct.foreignProductId = :foreignProductId
              and detail.sourceGeneralAccountId = :sourceGeneralAccountId
              and detail.registrableStockId is null
            """)
    boolean existsNullOriginByAccountAndProductAndSourceGeneralAccount(
            @Param("accountId") Long accountId,
            @Param("foreignProductId") Long foreignProductId,
            @Param("sourceGeneralAccountId") Long sourceGeneralAccountId);
}
