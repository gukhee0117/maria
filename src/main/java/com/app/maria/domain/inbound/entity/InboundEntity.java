package com.app.maria.domain.inbound.entity;

import com.app.maria.domain.account.entity.AccountEntity;
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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "inbound")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InboundEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "inbound_id")
    private Long inboundId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private AccountEntity account;

    @Column(name = "requested_qty", nullable = false, precision = 15, scale = 4)
    private BigDecimal requestedQty;

    @Column(name = "current_holding_at_request", precision = 15, scale = 4)
    private BigDecimal currentHoldingAtRequest;

    @Column(name = "approved_qty", nullable = false, precision = 15, scale = 4)
    private BigDecimal approvedQty;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    /** 업무시각은 BusinessClockService에서 받아 전달한다. 이슈 1은 현재 잔고를 추정해 저장하지 않는다. */
    public static InboundEntity create(
            AccountEntity account,
            BigDecimal requestedQty,
            BigDecimal approvedQty,
            LocalDateTime processedAt) {
        InboundEntity inbound = new InboundEntity();
        inbound.account = account;
        inbound.requestedQty = requestedQty;
        inbound.approvedQty = approvedQty;
        inbound.processedAt = processedAt;
        return inbound;
    }
}
