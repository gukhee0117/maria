package com.app.maria.domain.stockpackage.vo;

import com.app.maria.domain.domestic.type.Type;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 패키지에 담을 수 있는지 판단하는 데 필요한 종목 정보. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockProductVO {
    private Long domesticProductId;
    private String ticker;
    private String name;
    private Type type;
    private BigDecimal domesticStockRatio;
    private LocalDate inceptionDate;

    /** KRX 업종. 주식만 있고 펀드는 null. */
    private String sectorName;
}
