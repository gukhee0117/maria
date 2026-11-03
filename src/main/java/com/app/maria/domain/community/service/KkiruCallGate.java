package com.app.maria.domain.community.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.springframework.stereotype.Component;

/** 끼루 전체 하루 상한 확인을 한 번에 하나씩 처리하게 하는 잠금(kiru_call_gate의 한 행). 호출한 트랜잭션이 끝날 때 풀린다. */
@Component
public class KkiruCallGate {
    @PersistenceContext private EntityManager entityManager;

    public void lockDailyLimit() {
        List<?> rows =
                entityManager
                        .createNativeQuery(
                                "SELECT gate_id FROM kiru_call_gate WHERE gate_id = 1 FOR UPDATE")
                        .getResultList();
        if (rows.isEmpty()) {
            throw new IllegalStateException(
                    "kiru_call_gate(gate_id=1) 행이 없습니다. 기준 DDL(db/01_ria_ddl.sql)로 DB를 다시 만들었는지 확인하세요.");
        }
    }
}
