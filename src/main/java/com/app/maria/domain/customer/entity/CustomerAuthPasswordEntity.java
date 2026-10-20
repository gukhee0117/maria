package com.app.maria.domain.customer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 기존 password 컬럼에는 BCrypt 해시만 저장한다. Entity를 API 응답으로 반환하지 않는다. */
@Entity
@Table(name = "customer_auth_password")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerAuthPasswordEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "auth_password_id")
    private Long authPasswordId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auth_id", nullable = false, unique = true)
    private CustomerAuthEntity auth;

    @Column(name = "password", nullable = false, length = 255)
    private String passwordHash;
}
