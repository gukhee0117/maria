package com.app.maria.domain.stockpackage.dto.response;

import com.app.maria.domain.stockpackage.vo.StockPackageVO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;

/** 패키지 목록의 한 줄. */
@Getter
public class StockPackageSummaryResponseDTO {
    private final Long stockPackageId;
    private final String name;
    private final BigDecimal totalAmount;
    private final int itemCount;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    public StockPackageSummaryResponseDTO(StockPackageVO vo) {
        this.stockPackageId = vo.getStockPackageId();
        this.name = vo.getName();
        this.totalAmount = vo.getTotalAmount();
        this.itemCount = vo.getItemCount() == null ? 0 : vo.getItemCount();
        this.createdAt = vo.getCreatedAt();
        this.updatedAt = vo.getUpdatedAt();
    }
}
