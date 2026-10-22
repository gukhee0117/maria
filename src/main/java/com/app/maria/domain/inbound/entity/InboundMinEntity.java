package com.app.maria.domain.inbound.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 기존 계산 근거 테이블. 현재 일반계좌 잔고를 의미하지 않는다. */
@Entity
@Table(name = "inbound_min")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InboundMinEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "inbound_min_id")
    private Long inboundMinId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inbound_detail_id", nullable = false)
    private InboundDetailEntity inboundDetail;

    @Column(name = "requested_qty", precision = 15, scale = 4)
    private BigDecimal requestedQty;

    @Column(name = "approved_qty", precision = 15, scale = 4)
    private BigDecimal approvedQty;

    @Column(name = "snapshot_qty", precision = 15, scale = 4)
    private BigDecimal snapshotQty;

    public static InboundMinEntity create(
            InboundDetailEntity inboundDetail,
            BigDecimal requestedQty,
            BigDecimal approvedQty,
            BigDecimal snapshotQty) {
        InboundMinEntity min = new InboundMinEntity();
        min.inboundDetail = inboundDetail;
        min.requestedQty = requestedQty;
        min.approvedQty = approvedQty;
        min.snapshotQty = snapshotQty;
        return min;
    }
}
