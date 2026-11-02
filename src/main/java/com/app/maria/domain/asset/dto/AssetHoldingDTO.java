package com.app.maria.domain.asset.dto;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AssetHoldingDTO {
    private String ticker;
    private BigDecimal qty;
}
