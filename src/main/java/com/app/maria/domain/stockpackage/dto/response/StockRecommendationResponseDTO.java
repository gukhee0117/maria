package com.app.maria.domain.stockpackage.dto.response;

import com.app.maria.domain.domestic.type.Type;
import com.app.maria.domain.stockpackage.vo.StockRecommendationVO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;

@Getter
public class StockRecommendationResponseDTO {
    private final List<ItemResponse> items;
    private final boolean profileAvailable;
    private final String profileLabel;
    private final Integer allowedMinGrade;
    private final String filter;
    private final String subtitle;
    private final String disclaimer;
    private final LocalDateTime checkedAt;

    public StockRecommendationResponseDTO(StockRecommendationVO vo) {
        this.items = vo.getItems().stream().map(ItemResponse::new).toList();
        this.profileAvailable = vo.isProfileAvailable();
        this.profileLabel = vo.getProfileLabel();
        this.allowedMinGrade = vo.getAllowedMinGrade();
        this.filter = vo.getFilter();
        this.subtitle = vo.getSubtitle();
        this.disclaimer = vo.getDisclaimer();
        this.checkedAt = vo.getCheckedAt();
    }

    @Getter
    public static class ItemResponse {
        private final Long domesticProductId;
        private final String ticker;
        private final String name;
        private final String market;
        private final Type type;
        private final String sectorName;
        private final List<String> themes;
        private final int riskGrade;
        private final BigDecimal volatilityPercent;

        private final List<String> reasons;

        public ItemResponse(StockRecommendationVO.Item vo) {
            this.domesticProductId = vo.domesticProductId();
            this.ticker = vo.ticker();
            this.name = vo.name();
            this.market = vo.market();
            this.type = vo.type();
            this.sectorName = vo.sectorName();
            this.themes = vo.themes();
            this.riskGrade = vo.riskGrade();
            this.volatilityPercent = vo.volatilityPercent();
            this.reasons = vo.reasons();
        }
    }
}
