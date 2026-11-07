package com.app.maria.domain.domestic.dto;

import com.app.maria.domain.domestic.type.DomesticStockStatus;
import com.app.maria.domain.domestic.type.Type;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.*;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@Builder
public class DomesticHoldingDTO {
    private Long domesticProductId;
    private String ticker;
    private String name;
    private String sectorName;
    private Type type;
    private BigDecimal domesticStockRatio;
    private LocalDate inceptionDate;
    private BigDecimal qty;
    private DomesticStockStatus status;
    private LocalDateTime lastPurchaseDate;
    private BigDecimal avgPurchasePrice;
    private Boolean currentlyPurchasable;
}
