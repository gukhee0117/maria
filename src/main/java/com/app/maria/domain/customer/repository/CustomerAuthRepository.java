package com.app.maria.domain.customer.repository;

import com.app.maria.domain.customer.entity.CustomerAuthEntity;
import com.app.maria.domain.customer.entity.CustomerAuthEntity.AuthType;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.Repository;

/** 로그인 조회 전용. 기존 DDL에 로그인ID UNIQUE가 없으므로 중복 LOCAL 계정은 서비스에서 거절한다. */
public interface CustomerAuthRepository extends Repository<CustomerAuthEntity, Long> {
    @EntityGraph(attributePaths = {"customer", "password"})
    List<CustomerAuthEntity> findTop2ByLoginIdAndTypeOrderByAuthIdAsc(
            String loginId, AuthType type);
}
