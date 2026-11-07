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
            List<String> reasons) {}
}
