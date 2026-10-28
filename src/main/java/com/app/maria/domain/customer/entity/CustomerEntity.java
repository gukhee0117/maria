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

    /** 가입으로 새로 만드는 고객. 투자성향은 설문 전이라 미진단이다. */
    public static CustomerEntity newUnassigned(
            String name, LocalDate birthDate, String ciHash, LocalDateTime createdAt) {
        CustomerEntity customer = new CustomerEntity();
        customer.name = name;
        customer.birthDate = birthDate;
        customer.ciHash = ciHash;
        customer.createdAt = createdAt;
        customer.investorType = InvestorType.UNASSIGNED;
        return customer;
    }

    /** 설문 진단 결과를 반영한다. 미진단(UNASSIGNED)은 진단 결과가 될 수 없다. */
    public void applyInvestorType(InvestorType diagnosed) {
        if (diagnosed == InvestorType.UNASSIGNED) {
            throw new IllegalArgumentException("미진단은 진단 결과로 반영할 수 없습니다.");
        }
        this.investorType = diagnosed;
    }
}
