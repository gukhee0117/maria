package com.app.maria.domain.risk.service;

import com.app.maria.domain.risk.mapper.RiskSyncMapper;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.stereotype.Component;

@Component
public class MariaDbRiskSyncLease implements RiskSyncLease {
    private final SqlSessionFactory factory;

    public MariaDbRiskSyncLease(SqlSessionFactory factory) {
        this.factory = factory;
    }

    @Override
    public boolean runIfAcquired(Runnable work) {
        // Named locks are connection-scoped: keep this session/connection for the whole pull.
        try (var session = factory.openSession(true)) {
            var mapper = session.getMapper(RiskSyncMapper.class);
            Integer acquired = mapper.acquireLock();
            if (acquired == null) throw new AppException(ErrorType.RISK_SYNC_CONFLICT);
            if (acquired != 1) return false;
            try {
                work.run();
            } finally {
                mapper.releaseLock();
            }
            return true;
        }
    }
}
