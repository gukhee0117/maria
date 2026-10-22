package com.app.maria.domain.foreignproduct.repository;

import com.app.maria.domain.foreignproduct.entity.ForeignProductEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.repository.Repository;

/** 호출 서비스가 증권사 API에서 확인한 본인 적격 종목 ID 집합으로 조회한다. */
public interface ForeignProductRepository extends Repository<ForeignProductEntity, Long> {
    List<ForeignProductEntity> findByForeignProductIdInOrderByTickerAscForeignProductIdAsc(
            Collection<Long> foreignProductIds);
}
