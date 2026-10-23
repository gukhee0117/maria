package com.app.maria.domain.registrablestock.dto;

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
public class RegistrableStockResponseDTO {
    private Long registrableStockId;
    private Long foreignProductId;
    private Long generalAccountId;
    private String accountNo;
    private GeneralAccountType accountType;
    private BigDecimal heldQty;
    private String sourceBroker;
    private LocalDateTime purchaseDate;
    private BigDecimal purchasePrice;
    private String purchaseCurrency;
    private BigDecimal purchaseFxRate;
}
