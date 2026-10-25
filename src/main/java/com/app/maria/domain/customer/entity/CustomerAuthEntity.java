package com.app.maria.domain.customer.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 고객은 여러 인증수단을 가질 수 있고 LOCAL 인증수단만 비밀번호를 사용한다. */
@Entity
@Table(name = "customer_auth")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerAuthEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "auth_id")
    private Long authId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private CustomerEntity customer;

    @Column(name = "login_id", nullable = false, length = 50)
    private String loginId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10)
    private AuthType type;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @OneToOne(mappedBy = "auth", fetch = FetchType.LAZY, cascade = CascadeType.PERSIST)
    private CustomerAuthPasswordEntity password;

    public void markLoggedIn(LocalDateTime at) {
        this.lastLoginAt = at;
    }

    /** LOCAL 계정과 비밀번호 행을 함께 만든다(저장할 때 비밀번호도 같이 저장된다). */
    public static CustomerAuthEntity newLocal(
            CustomerEntity customer, String loginId, String passwordHash) {
        CustomerAuthEntity auth = new CustomerAuthEntity();
        auth.customer = customer;
        auth.loginId = loginId;
        auth.type = AuthType.LOCAL;
        auth.password = CustomerAuthPasswordEntity.of(auth, passwordHash);
        return auth;
    }

    public enum AuthType {
        LOCAL,
        GOOGLE,
        KAKAO,
        NAVER
    }
}
