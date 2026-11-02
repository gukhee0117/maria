package com.app.maria.domain.asset.dto;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AssetMonthlyPurchaseDTO {
    private int purchaseYear;
    private int purchaseMonth;
    private BigDecimal buyAmount;
}
