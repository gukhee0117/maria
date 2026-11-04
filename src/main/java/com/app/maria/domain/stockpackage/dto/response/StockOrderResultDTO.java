package com.app.maria.domain.stockpackage.dto.response;

import com.app.maria.domain.domestic.type.DomesticOrderSide;
import com.app.maria.domain.domestic.type.DomesticOrderStatus;
import com.app.maria.domain.stockpackage.vo.StockOrderVO;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 주문 한 건의 결과. 거부된 주문은 price·amount가 null이고 rejectReason·rejectMessage가 채워진다. */
public record StockOrderResultDTO(
        Long orderId,
        Long stockPackageId,
        String packageName,
        Long domesticProductId,
        String ticker,
        String name,
        DomesticOrderSide side,
        BigDecimal qty,
        BigDecimal price,
        BigDecimal amount,
        BigDecimal feeAmount,
        BigDecimal taxAmount,
        DomesticOrderStatus status,
        String rejectReason,
        String rejectMessage,
        LocalDateTime processedAt) {

    public StockOrderResultDTO(StockOrderVO vo) {
        this(
                vo.getDomesticOrderId(),
                vo.getStockPackageId(),
                vo.getPackageName(),
                vo.getDomesticProductId(),
                vo.getTicker(),
                vo.getName(),
                vo.getSide(),
                vo.getQty(),
                vo.getPrice(),
                vo.getAmount(),
                vo.getFeeAmount(),
                vo.getTaxAmount(),
                vo.getStatus(),
                vo.getRejectReason() == null ? null : vo.getRejectReason().name(),
                vo.getRejectReason() == null ? null : vo.getRejectReason().getMessage(),
                vo.getProcessedAt());
    }
}
