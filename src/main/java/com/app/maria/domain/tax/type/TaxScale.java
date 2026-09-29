package com.app.maria.domain.tax.type;

import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum TaxScale {
    RATIO(4),
    AMOUNT(2),
    DIVIDE(12);

    private final int scale;

    public int scale() {
        return scale;
    }

    public BigDecimal round(BigDecimal value) {
        return value.setScale(scale, RoundingMode.HALF_UP);
    }
}
