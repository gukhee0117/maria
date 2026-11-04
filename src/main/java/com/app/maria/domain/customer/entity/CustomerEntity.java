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

    @Column(name = "email", length = 100)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "investor_type", nullable = false, length = 20)
    private InvestorType investorType;

    @Column(name = "ci_hash", nullable = false, length = 64, unique = true)
    private String ciHash;

    /** 커뮤니티에서 보여줄 이름. 앱은 조회만 한다. 시드가 채우고, 없는 고객은 가입할 때 무작위로 붙인다(UNIQUE) */
    @Column(name = "nickname", length = 20)
    private String nickname;

    /** 커뮤니티 글·댓글에 투자성향 이름을 보여줄지. 기본은 비공개 */
    @Column(name = "investor_type_public", nullable = false)
    private boolean investorTypePublic;

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

    /** 닉네임이 없는 고객에게 처음 닉네임을 붙인다. 변경은 지원하지 않는다. */
    public void assignNickname(String nickname) {
        this.nickname = nickname;
    }

    /** 연락처를 바꾼다. null인 항목은 그대로 둔다. 형식 검사와 정규화는 호출하는 쪽이 한다. */
    public void changeContact(String phone, String email) {
        if (phone != null) {
            this.phone = phone;
        }
        if (email != null) {
            this.email = email;
        }
    }

    public void changeInvestorTypePublic(boolean investorTypePublic) {
        this.investorTypePublic = investorTypePublic;
    }

    /** 설문 진단 결과를 반영한다. 미진단(UNASSIGNED)은 진단 결과가 될 수 없다. */
    public void applyInvestorType(InvestorType diagnosed) {
        if (diagnosed == InvestorType.UNASSIGNED) {
            throw new IllegalArgumentException("미진단은 진단 결과로 반영할 수 없습니다.");
        }
        this.investorType = diagnosed;
    }
}
