package com.app.maria.domain.stockpackage.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** stock_package 한 행. itemCount는 목록 조회에서만, items는 상세 조회에서만 채워진다. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockPackageVO {
    private Long stockPackageId;
    private Long customerId;
    private String name;
    private BigDecimal totalAmount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer itemCount;
    @Builder.Default private List<StockPackageItemVO> items = new ArrayList<>();
}
