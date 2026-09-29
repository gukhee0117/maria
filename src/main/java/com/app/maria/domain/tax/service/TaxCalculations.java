package com.app.maria.domain.tax.service;

import com.app.maria.domain.tax.dto.SellLotDTO;
import com.app.maria.domain.tax.type.TaxScale;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TaxCalculations {

    public static BigDecimal gainAmount(SellLotDTO lot) {
        BigDecimal purchaseCost =
                lot.getPurchasePrice().multiply(lot.getPurchaseFxRate()).multiply(lot.getSellQty());
        return lot.getFinalAmount().subtract(purchaseCost);
    }

    public static BigDecimal weightOf(BigDecimal ruleValue) {
        return TaxScale.RATIO.round(
                ruleValue.divide(
                        BigDecimal.valueOf(100), TaxScale.DIVIDE.scale(), RoundingMode.HALF_UP));
    }
}
