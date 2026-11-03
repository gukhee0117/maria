package com.app.maria.domain.community.repository;

import com.app.maria.domain.community.entity.KkiruRetryLogEntity;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KkiruRetryLogRepository extends JpaRepository<KkiruRetryLogEntity, Long> {
    /** 이 고객이 since 이후 한 다시 묻기 수. */
    long countByCustomerIdAndRetriedAtGreaterThanEqual(Long customerId, LocalDateTime since);

    /** 모든 고객이 since 이후 한 다시 묻기 수. */
    long countByRetriedAtGreaterThanEqual(LocalDateTime since);
}
