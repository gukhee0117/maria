package com.app.maria.domain.stockpackage.dto.response;

import com.app.maria.domain.stockpackage.vo.StockPackageCheckVO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.Getter;

/** 패키지 점검 응답. null인 값은 계산할 수 없다는 뜻이고 unavailableFields에 그 이름이 있다. */
@Getter
public class StockPackageCheckResponseDTO {
    private final int itemCount;
    private final BigDecimal avgRiskGrade;
    private final boolean profileAvailable;
    private final Integer outOfProfileCount;
    private final List<String> outOfProfileNames;
    private final BigDecimal volatilityPercent;
    private final OffsetDateTime volatilityAsOf;
    private final BigDecimal hhi;
    private final BigDecimal equalWeightHhi;
    private final WeightedResponse topItem;
    private final List<ContributionResponse> contributions;
    private final List<WeightedResponse> themes;
    private final List<WeightedResponse> sectors;
    private final List<String> unavailableFields;
    private final Integer allowedMinGrade;
    private final List<ExplanationBlockResponse> explanation;
    private final String disclaimer;
    private final LocalDateTime checkedAt;

    public StockPackageCheckResponseDTO(StockPackageCheckVO vo) {
        this.itemCount = vo.getItemCount();
        this.avgRiskGrade = vo.getAvgRiskGrade();
        this.profileAvailable = vo.isProfileAvailable();
        this.outOfProfileCount = vo.getOutOfProfileCount();
        this.outOfProfileNames = vo.getOutOfProfileNames();
        this.volatilityPercent = vo.getVolatilityPercent();
        this.volatilityAsOf = vo.getVolatilityAsOf();
        this.hhi = vo.getHhi();
        this.equalWeightHhi = vo.getEqualWeightHhi();
        this.topItem = new WeightedResponse(vo.getTopItem());
        this.contributions = vo.getContributions().stream().map(ContributionResponse::new).toList();
        this.themes = vo.getThemes().stream().map(WeightedResponse::new).toList();
        this.sectors = vo.getSectors().stream().map(WeightedResponse::new).toList();
        this.unavailableFields = vo.getUnavailableFields();
        this.allowedMinGrade = vo.getAllowedMinGrade();
        this.explanation = vo.getExplanation().stream().map(ExplanationBlockResponse::new).toList();
        this.disclaimer = vo.getDisclaimer();
        this.checkedAt = vo.getCheckedAt();
    }

    /** 지표 하나의 설명. metric은 화면이 카드와 짝지을 때 쓰는 이름이다. */
    @Getter
    public static class ExplanationBlockResponse {
        private final String metric;
        private final String title;
        private final String definition;
        private final List<String> facts;

        public ExplanationBlockResponse(StockPackageCheckVO.ExplanationBlock vo) {
            this.metric = vo.metric().name();
            this.title = vo.title();
            this.definition = vo.definition();
            this.facts = vo.facts();
        }
    }

    @Getter
    public static class WeightedResponse {
        private final String name;
        private final BigDecimal weight;

        public WeightedResponse(StockPackageCheckVO.Weighted vo) {
            this.name = vo.name();
            this.weight = vo.weight();
        }
    }

    @Getter
    public static class ContributionResponse {
        private final Long domesticProductId;
        private final String name;
        private final BigDecimal weight;
        private final BigDecimal hhiSharePercent;
        private final Integer riskGrade;
        private final Boolean outOfProfile;

        public ContributionResponse(StockPackageCheckVO.Contribution vo) {
            this.domesticProductId = vo.domesticProductId();
            this.name = vo.name();
            this.weight = vo.weight();
            this.hhiSharePercent = vo.hhiSharePercent();
            this.riskGrade = vo.riskGrade();
            this.outOfProfile = vo.outOfProfile();
        }
    }
}
