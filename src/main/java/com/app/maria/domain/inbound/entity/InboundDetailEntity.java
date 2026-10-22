package com.app.maria.domain.inbound.entity;

import com.app.maria.domain.foreignproduct.entity.ForeignProductEntity;
import com.app.maria.domain.registrablestock.type.GeneralAccountType;
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
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "inbound_detail")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InboundDetailEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "inbound_detail_id")
    private Long inboundDetailId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inbound_id", nullable = false)
    private InboundEntity inbound;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "foreign_product_id", nullable = false)
    private ForeignProductEntity foreignProduct;

    // 외부 DB 원본 ID. 기존 행은 NULL 허용하며 외부 DB FK와 UNIQUE를 만들지 않는다.
    @Column(name = "registrable_stock_id")
    private Long registrableStockId;

    @Column(name = "source_broker", length = 20)
    private String sourceBroker;

    @Column(name = "source_general_account_id")
    private Long sourceGeneralAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", length = 30)
    private GeneralAccountType accountType;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    @Column(name = "purchase_date", nullable = false)
    private LocalDateTime purchaseDate;

    @Column(name = "purchase_price", nullable = false, precision = 15, scale = 4)
    private BigDecimal purchasePrice;

    @Column(name = "purchase_currency", nullable = false, length = 10)
    private String purchaseCurrency;

    @Column(name = "purchase_fx_rate", nullable = false, precision = 15, scale = 4)
    private BigDecimal purchaseFxRate;

    @Column(name = "qty", nullable = false, precision = 15, scale = 4)
    private BigDecimal qty;

    @Column(name = "current_qty", nullable = false, precision = 15, scale = 4)
    private BigDecimal currentQty;

    /** 신규 고객 입고의 원본·출처는 필수이며 초기 잔여 수량은 입고 수량과 같다. */
    public static InboundDetailEntity create(
            InboundEntity inbound,
            ForeignProductEntity foreignProduct,
            Long registrableStockId,
            Long sourceGeneralAccountId,
            GeneralAccountType accountType,
            String sourceBroker,
            LocalDateTime recordedAt,
            LocalDateTime purchaseDate,
            BigDecimal purchasePrice,
            String purchaseCurrency,
            BigDecimal purchaseFxRate,
            BigDecimal qty) {
        InboundDetailEntity detail = new InboundDetailEntity();
        detail.inbound = inbound;
        detail.foreignProduct = foreignProduct;
        detail.registrableStockId = Objects.requireNonNull(registrableStockId, "원본 주식 ID는 필수입니다.");
        detail.sourceGeneralAccountId =
                Objects.requireNonNull(sourceGeneralAccountId, "출처 일반계좌는 필수입니다.");
        detail.accountType = accountType;
        detail.sourceBroker = sourceBroker;
        detail.recordedAt = recordedAt;
        detail.purchaseDate = purchaseDate;
        detail.purchasePrice = purchasePrice;
        detail.purchaseCurrency = purchaseCurrency;
        detail.purchaseFxRate = purchaseFxRate;
        detail.qty = qty;
        detail.currentQty = qty;
        return detail;
    }
}
