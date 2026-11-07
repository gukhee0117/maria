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

    /** 추천 기준: SURVEY(설문 투자성향) 또는 TRADING(거래에서 본 경향). */
    private final String basis;

    /** 이 기준으로 추천을 계산할 수 있는지. 설문은 진단이 유효할 때, 거래는 체결 매수가 최소 건수 이상일 때 true다. */
    private final boolean basisAvailable;

    /** 거래 기준일 때 추천의 바탕이 된 경향 사실(평균 매수 등급, 많이 산 업종·테마) 문장. */
    private final List<String> basisFacts;

    private final int tradeBuyCount;
    private final int minBuyCount;
    private final String subtitle;
    private final String disclaimer;
    private final LocalDateTime checkedAt;

    /** 평균 매수 등급이 설문 허용 범위 밖이라 목표 등급을 허용 범위 경계값으로 맞췄는지(거래 기준에서만 true일 수 있다). */
    private final boolean targetAdjusted;

    /** 추천을 고를 때 기준으로 쓴 목표 등급(보정 뒤 값). 계산하지 못했으면 null. */
    private final BigDecimal targetGrade;

    /** 후보로 본 위험등급 범위(양 끝 포함, 1이 가장 위험). 계산하지 못했으면 null. */
    private final Integer gradeFrom;

    private final Integer gradeTo;

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
            List<String> reasons,
            boolean sectorMatched,
            boolean themeMatched,
            String matchedTheme) {}
}
