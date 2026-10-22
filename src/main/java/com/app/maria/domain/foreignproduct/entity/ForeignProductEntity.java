package com.app.maria.domain.foreignproduct.entity;

import com.app.maria.domain.foreignproduct.type.ForeignProductType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 기존 foreign_product 테이블 매핑. 적격성·보유 여부는 증권사 조회 결과로 판단한다. */
@Entity
@Table(name = "foreign_product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ForeignProductEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "foreign_product_id")
    private Long foreignProductId;

    @Column(name = "ticker", nullable = false, length = 20)
    private String ticker;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "market", nullable = false, length = 50)
    private String market;

    @Column(name = "currency", length = 10)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 15)
    private ForeignProductType foreignProductType;
}
