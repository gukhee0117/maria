package com.app.maria.domain.settlement.dto.response;

import com.app.maria.domain.settlement.type.SettlementStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 고객 화면용 정산 한 건. 매도 lot마다 한 건이다(한 주문이 여러 lot에 걸치면 정산도 lot 수만큼 나뉜다). finalAt은 가정산 때 이미 정해지는 확정산
 * 예정일이라 PROVISIONAL에서도 null이 아니다. 확정 여부는 settlementStatus로 판단하고, finalAmount는 확정산 전(PROVISIONAL)에
 * null이다.
 */
@Getter
@Setter
@NoArgsConstructor
public class CustomerSettlementResponseDTO {
    private Long exchangeId;
    private Long orderId;
    private String ticker;
    private String name;
    private BigDecimal sellQty;
    private BigDecimal provisionalAmount;
    private LocalDateTime provisionalAt;
    private BigDecimal finalAmount;
    private LocalDateTime finalAt;
    private SettlementStatus settlementStatus;
}
