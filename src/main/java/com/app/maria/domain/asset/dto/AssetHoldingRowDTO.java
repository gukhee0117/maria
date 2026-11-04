package com.app.maria.domain.asset.dto;

import com.app.maria.domain.domestic.type.DomesticStockStatus;
import com.app.maria.domain.domestic.type.Type;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 보유 종목 표 한 행의 저장 데이터(시세·위험등급 붙이기 전). */
@Getter
@Setter
@NoArgsConstructor
public class AssetHoldingRowDTO {
    private String ticker;
    private String name;
    private String sectorName;
    private Type type;
    private DomesticStockStatus status;
    private BigDecimal qty;
    private BigDecimal avgPurchasePrice;
}
