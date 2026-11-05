package com.app.maria.domain.sellorder.dto.response;

import com.app.maria.domain.sellorder.type.SellOrderStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 고객 화면용 매도 주문 한 건. 주문은 입고 lot마다 한 행이다(lot에 연결되기 전에 거부된 REJECTED 주문은 inboundDetailId가 null이다). 저장되는
 * 주문은 체결(EXECUTED)과 거부(REJECTED)뿐이고, processedAt은 그 체결 또는 거부가 처리된 업무 시각이다. orderAmount는 저장된 기준가로 다시
 * 계산해 소수 둘째 자리로 반올림한 표시용 근사 금액이라 주문 시점 한도 판정 금액과 몇 원 차이 날 수 있고 실제 정산금액도 아니다(정산은 settlements).
 */
@Getter
@Setter
@NoArgsConstructor
public class CustomerSellOrderResponseDTO {
    private Long orderId;
    private Long inboundDetailId;
    private String ticker;
    private String name;
    private BigDecimal sellQty;
    private BigDecimal basePrice;
    private BigDecimal orderAmount;
    private SellOrderStatus status;
    private LocalDateTime processedAt;
}
