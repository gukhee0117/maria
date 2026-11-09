package com.app.maria.domain.sellorder.dto.response;

import com.app.maria.domain.sellorder.type.SellOrderStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;

/**
 * 고객 매도 신청 결과. status가 EXECUTED면 입고 lot마다 한 건씩(orders) 체결된 것이고, REJECTED면 한도 초과로 거부돼 거부 한 건만 담긴다(거부
 * 사유는 저장하지 않으며 한도 초과다). totalQty·totalAmount는 orders의 합이고 금액은 주문 시점 기준금액(수량 × 기준가, 소수 둘째 자리 반올림)이다.
 */
@Getter
public class CustomerSellOrderResultResponseDTO {
    private final SellOrderStatus status;
    private final BigDecimal totalQty;
    private final BigDecimal totalAmount;
    private final List<Order> orders;

    private CustomerSellOrderResultResponseDTO(
            SellOrderStatus status,
            BigDecimal totalQty,
            BigDecimal totalAmount,
            List<Order> orders) {
        this.status = status;
        this.totalQty = totalQty;
        this.totalAmount = totalAmount;
        this.orders = orders;
    }

    public static CustomerSellOrderResultResponseDTO of(List<SellOrderResponseDTO> placed) {
        List<Order> orders = placed.stream().map(Order::new).toList();
        SellOrderStatus status =
                placed.stream().anyMatch(o -> o.getStatus() == SellOrderStatus.REJECTED)
                        ? SellOrderStatus.REJECTED
                        : SellOrderStatus.EXECUTED;
        BigDecimal totalQty =
                orders.stream().map(Order::getSellQty).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalAmount =
                orders.stream().map(Order::getOrderAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CustomerSellOrderResultResponseDTO(status, totalQty, totalAmount, orders);
    }

    @Getter
    public static class Order {
        private final Long orderId;
        private final Long inboundDetailId;
        private final BigDecimal sellQty;
        private final BigDecimal basePrice;
        private final BigDecimal orderAmount;
        private final SellOrderStatus status;
        private final LocalDateTime processedAt;

        private Order(SellOrderResponseDTO placed) {
            this.orderId = placed.getOrderId();
            this.inboundDetailId = placed.getInboundDetailId();
            this.sellQty = placed.getSellQty();
            this.basePrice = placed.getBasePrice();
            this.orderAmount =
                    placed.getSellQty()
                            .multiply(placed.getBasePrice())
                            .setScale(2, java.math.RoundingMode.HALF_UP);
            this.status = placed.getStatus();
            this.processedAt = placed.getProcessedAt();
        }
    }
}
