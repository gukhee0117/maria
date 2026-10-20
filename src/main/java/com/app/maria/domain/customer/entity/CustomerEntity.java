package com.app.maria.domain.customer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 기존 customer 테이블 매핑. 비밀번호는 고객 정보에 포함하지 않는다. */
@Entity
@Table(name = "customer")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "birth_date", nullable = false)
    private LocalDate birthDate;

    @Column(name = "phone", length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "investor_type", nullable = false, length = 20)
    private InvestorType investorType;

    @Column(name = "ci_hash", nullable = false, length = 64, unique = true)
    private String ciHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public enum InvestorType {
        STABLE,
        CONSERVATIVE,
        NEUTRAL,
        ACTIVE,
        AGGRESSIVE
    }
}
