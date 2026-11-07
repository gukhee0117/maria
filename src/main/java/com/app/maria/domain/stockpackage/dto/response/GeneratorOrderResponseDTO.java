package com.app.maria.domain.stockpackage.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 생성기 주문 결과. status는 EXECUTED 또는 REJECTED. 주문을 넣기 전에 거절한 경우(제외 고객·계좌 없음·보유 없음·예수금 부족 등)는
 * domesticOrderId가 null이다. amount는 예수금 변동(매수는 음수)이고 processedAt은 maria 업무시계 시각이다.
 */
public record GeneratorOrderResponseDTO(
        String status,
        String rejectReason,
        Long domesticOrderId,
        String ticker,
        BigDecimal qty,
        BigDecimal price,
        BigDecimal amount,
        LocalDateTime processedAt) {

    public static GeneratorOrderResponseDTO rejected(String reason) {
        return new GeneratorOrderResponseDTO(
                "REJECTED", reason, null, null, null, null, null, null);
    }

    public static GeneratorOrderResponseDTO from(StockOrderResultDTO order) {
        return new GeneratorOrderResponseDTO(
                order.status().name(),
                order.rejectReason(),
                order.orderId(),
                order.ticker(),
                order.qty(),
                order.price(),
                order.amount(),
                order.processedAt());
    }
}
