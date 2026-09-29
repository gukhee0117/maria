package com.app.maria.domain.tax.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ExternalBuyDTO {
    private Long accountId;
    private LocalDate tradeDate;
    private BigDecimal netBuyAmount;
    private String productLabel;
}
