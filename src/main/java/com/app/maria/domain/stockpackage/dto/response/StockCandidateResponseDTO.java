package com.app.maria.domain.stockpackage.dto.response;

import com.app.maria.domain.domestic.type.Type;
import com.app.maria.domain.stockpackage.vo.StockCandidateListVO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;

/** 후보 보기 응답. 순위·점수·이유가 없고 조건에 맞는 상품의 사실 정보만 있다. */
@Getter
public class StockCandidateResponseDTO {
    private final List<ItemResponse> items;
    private final int totalCount;
    private final boolean truncated;
    private final boolean profileAvailable;
    private final Integer allowedMinGrade;
    private final boolean includeOutOfProfile;
    private final String sort;
    private final String direction;
    private final List<String> summary;
    private final String disclaimer;
    private final LocalDateTime checkedAt;

    public StockCandidateResponseDTO(StockCandidateListVO vo) {
        this.items = vo.getItems().stream().map(ItemResponse::new).toList();
        this.totalCount = vo.getTotalCount();
        this.truncated = vo.isTruncated();
        this.profileAvailable = vo.isProfileAvailable();
        this.allowedMinGrade = vo.getAllowedMinGrade();
        this.includeOutOfProfile = vo.isIncludeOutOfProfile();
        this.sort = vo.getSort();
        this.direction = vo.getDirection();
        this.summary = vo.getSummary();
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
        private final Integer riskGrade;
        private final BigDecimal volatilityPercent;
        private final Boolean outOfProfile;
        private final boolean selectable;
        private final String unselectableReason;

        public ItemResponse(StockCandidateListVO.Row vo) {
            this.domesticProductId = vo.domesticProductId();
            this.ticker = vo.ticker();
            this.name = vo.name();
            this.market = vo.market();
            this.type = vo.type();
            this.sectorName = vo.sectorName();
            this.themes = vo.themes();
            this.riskGrade = vo.riskGrade();
            this.volatilityPercent = vo.volatilityPercent();
            this.outOfProfile = vo.outOfProfile();
            this.selectable = vo.selectable();
            this.unselectableReason = vo.unselectableReason();
        }
    }
}
