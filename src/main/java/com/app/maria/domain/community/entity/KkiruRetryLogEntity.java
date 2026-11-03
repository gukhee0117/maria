package com.app.maria.domain.community.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 끼루 다시 묻기 한 번의 기록. 하루 호출 예산을 셀 때 오늘 한 재시도 수를 구하는 데만 쓴다. */
@Entity
@Table(name = "kiru_retry_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KkiruRetryLogEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "kiru_retry_log_id")
    private Long kiruRetryLogId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "retried_at", nullable = false)
    private LocalDateTime retriedAt;

    public static KkiruRetryLogEntity of(Long customerId, LocalDateTime retriedAt) {
        KkiruRetryLogEntity log = new KkiruRetryLogEntity();
        log.customerId = customerId;
        log.retriedAt = retriedAt;
        return log;
    }
}
