package com.app.maria.domain.inbound.dto;

import com.app.maria.domain.registrablestock.type.GeneralAccountType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.*;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@Builder
public class InboundDetailDTO {
    private Long inboundDetailId;
    private GeneralAccountType accountType;
    private Long inboundId;
    private Long foreignProductId;
    private Long registrableStockId;
    private String sourceBroker;
    private BigDecimal qty;
    private BigDecimal currentQty;
    private LocalDateTime recordedAt;
    private LocalDateTime purchaseDate;
    private BigDecimal purchasePrice;
    private String purchaseCurrency;
    private BigDecimal purchaseFxRate;
    private Long sourceGeneralAccountId;
}
