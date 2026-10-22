package com.app.maria.domain.inbound.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.*;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@Builder
public class InboundListDTO {
    private Long inboundId;
    private Long foreignProductId;
    private Long accountId;
    private String accountNo;
    private String customerName;
    private String ticker;
    private String productName;
    private String sourceBroker;
    private BigDecimal requestedQty;
    private BigDecimal currentHoldingAtRequest;
    private BigDecimal snapshotQty;
    private BigDecimal approvedQty;
    private BigDecimal remainingQty;
    private LocalDateTime processedAt;
    private List<InboundLotDTO> lots;
}
