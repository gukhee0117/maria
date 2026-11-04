package com.app.maria.domain.stockpackage.vo;

import com.app.maria.domain.domestic.type.Type;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * stock_package_item 한 행에 종목 이름이 붙은 형태. riskGrade와 outOfProfile은 DB 값이 아니라 상세 조회 때 서비스가 채운다. 등급이
 * 없거나 성향을 판단할 수 없으면 null이다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockPackageItemVO {
    private Long stockPackageItemId;
    private Long stockPackageId;
    private Long domesticProductId;
    private String ticker;
    private String name;
    private Type type;
    private String market;
    private BigDecimal weight;
    private LocalDateTime unsuitableConfirmedAt;
    private LocalDateTime createdAt;
    private Integer riskGrade;
    private Boolean outOfProfile;
}
