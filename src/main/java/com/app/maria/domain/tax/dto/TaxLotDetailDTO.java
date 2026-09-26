package com.app.maria.domain.tax.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class TaxLotDetailDTO {
    private String productLabel;
    private LocalDate finalAt;
    private BigDecimal sellAmount;
    private BigDecimal gainAmount;
}
