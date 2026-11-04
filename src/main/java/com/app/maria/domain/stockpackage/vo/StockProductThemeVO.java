package com.app.maria.domain.stockpackage.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 종목이 속한 테마 한 줄. 한 종목이 여러 테마에 속할 수 있다. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockProductThemeVO {
    private Long domesticProductId;
    private String themeName;
}
