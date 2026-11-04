package com.app.maria.domain.stockpackage.vo;

import com.app.maria.domain.domestic.type.Type;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 후보 보기에서 조건에 맞는 종목 마스터 한 행. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockCandidateProductVO {
    private Long domesticProductId;
    private String ticker;
    private String name;
    private String market;
    private Type type;
    private BigDecimal domesticStockRatio;
    private LocalDate inceptionDate;
    private String sectorName;
}
