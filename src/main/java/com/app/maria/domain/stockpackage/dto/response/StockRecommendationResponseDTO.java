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
    private final String basis;
    private final boolean basisAvailable;
    private final List<String> basisFacts;
    private final int tradeBuyCount;
    private final int minBuyCount;
    private final String subtitle;
    private final String disclaimer;
    private final LocalDateTime checkedAt;

    /** true면 평균 매수 등급이 설문 허용 범위 밖이라 목표 등급을 허용 범위 경계값으로 보정했다. */
    private final boolean targetAdjusted;

    /** 추천을 고를 때 기준으로 쓴 목표 등급(보정 뒤). */
    private final BigDecimal targetGrade;

    /** 후보로 본 위험등급 범위(양 끝 포함). */
    private final Integer gradeFrom;

    private final Integer gradeTo;

    public StockRecommendationResponseDTO(StockRecommendationVO vo) {
        this.items = vo.getItems().stream().map(ItemResponse::new).toList();
        this.profileAvailable = vo.isProfileAvailable();
        this.profileLabel = vo.getProfileLabel();
        this.allowedMinGrade = vo.getAllowedMinGrade();
        this.filter = vo.getFilter();
        this.basis = vo.getBasis();
        this.basisAvailable = vo.isBasisAvailable();
        this.basisFacts = vo.getBasisFacts();
        this.tradeBuyCount = vo.getTradeBuyCount();
        this.minBuyCount = vo.getMinBuyCount();
        this.subtitle = vo.getSubtitle();
        this.disclaimer = vo.getDisclaimer();
        this.checkedAt = vo.getCheckedAt();
        this.targetAdjusted = vo.isTargetAdjusted();
        this.targetGrade = vo.getTargetGrade();
        this.gradeFrom = vo.getGradeFrom();
        this.gradeTo = vo.getGradeTo();
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

        /** true면 내 설문 투자성향 허용 범위보다 위험한 상품이다(거래 기준 추천에서 나올 수 있다). */
        private final boolean outOfProfile;

        private final List<String> reasons;

        /** 가장 많이 산 업종과 같은 업종인지(거래 데이터가 없으면 항상 false). */
        private final boolean sectorMatched;

        /** 가장 많이 산 테마에 속하는지(거래 데이터가 없으면 항상 false). */
        private final boolean themeMatched;

        /** 가장 많이 산 테마와 일치한 테마 이름. themeMatched가 false면 null이다. */
        private final String matchedTheme;

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
            this.outOfProfile = vo.outOfProfile();
            this.reasons = vo.reasons();
            this.sectorMatched = vo.sectorMatched();
            this.themeMatched = vo.themeMatched();
            this.matchedTheme = vo.matchedTheme();
        }
    }
}
