package com.app.maria.domain.stockpackage.vo;

import com.app.maria.domain.domestic.type.Type;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/** 성향별 추천 결과. 이유 문장은 서버가 검증된 사실(등급·변동성·테마)에 끼워 만든 것이다. */
@Getter
@Builder
public class StockRecommendationVO {
    private final List<Item> items;
    private final boolean profileAvailable;
    private final String profileLabel;
    private final Integer allowedMinGrade;
    private final String filter;

    /** 고객이 질문으로 고른 테마. 있으면 이 테마 안의 설문 허용 범위 안 상품에서 골랐다. 없으면 null이다. */
    private final String theme;

    /** 테마 안에서 고를 수 있었지만 설문 허용 범위 밖이라 추천하지 않은 상품 수. 테마가 없으면 0이다. */
    private final int outOfProfileCount;

    private final String subtitle;
    private final String disclaimer;
    private final LocalDateTime checkedAt;

    public record Item(
            Long domesticProductId,
            String ticker,
            String name,
            String market,
            Type type,
            String sectorName,
            List<String> themes,
            int riskGrade,
            BigDecimal volatilityPercent,
            boolean outOfProfile,
            List<String> reasons) {}
}
