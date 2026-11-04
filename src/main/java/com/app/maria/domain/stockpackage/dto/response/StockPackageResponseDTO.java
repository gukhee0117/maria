package com.app.maria.domain.stockpackage.dto.response;

import com.app.maria.domain.stockpackage.vo.StockPackageItemVO;
import com.app.maria.domain.stockpackage.vo.StockPackageVO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;

/** 패키지 상세 응답. riskGrade가 null이면 등급 없음, outOfProfile이 null이면 성향을 판단할 수 없는 상태다. */
@Getter
public class StockPackageResponseDTO {
    private final Long stockPackageId;
    private final String name;
    private final BigDecimal totalAmount;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
    private final List<ItemResponse> items;

    public StockPackageResponseDTO(StockPackageVO vo) {
        this.stockPackageId = vo.getStockPackageId();
        this.name = vo.getName();
        this.totalAmount = vo.getTotalAmount();
        this.createdAt = vo.getCreatedAt();
        this.updatedAt = vo.getUpdatedAt();
        this.items = vo.getItems().stream().map(ItemResponse::new).toList();
    }

    @Getter
    public static class ItemResponse {
        private final Long domesticProductId;
        private final String ticker;
        private final String name;
        private final BigDecimal weight;
        private final Integer riskGrade;
        private final Boolean outOfProfile;
        private final LocalDateTime unsuitableConfirmedAt;

        public ItemResponse(StockPackageItemVO vo) {
            this.domesticProductId = vo.getDomesticProductId();
            this.ticker = vo.getTicker();
            this.name = vo.getName();
            this.weight = vo.getWeight();
            this.riskGrade = vo.getRiskGrade();
            this.outOfProfile = vo.getOutOfProfile();
            this.unsuitableConfirmedAt = vo.getUnsuitableConfirmedAt();
        }
    }
}
