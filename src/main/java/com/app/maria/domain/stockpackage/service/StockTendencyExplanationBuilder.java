package com.app.maria.domain.stockpackage.service;

import com.app.maria.domain.stockpackage.type.CheckMetric;
import com.app.maria.domain.stockpackage.type.StockPackageSentence;
import com.app.maria.domain.stockpackage.vo.StockPackageCheckVO.ExplanationBlock;
import com.app.maria.domain.stockpackage.vo.StockTendencyVO.Comparison;
import com.app.maria.domain.stockpackage.vo.StockTendencyVO.GradeShare;
import com.app.maria.domain.stockpackage.vo.StockTendencyVO.Survey;
import com.app.maria.domain.stockpackage.vo.StockTendencyVO.Trading;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 설문 성향과 거래에서 본 경향의 설명을 문장은행으로 조립한다. 문장을 새로 만들지 않고 계산된 값만 끼워 넣는다. */
public final class StockTendencyExplanationBuilder {
    private StockTendencyExplanationBuilder() {}

    public static List<ExplanationBlock> build(Survey survey, Trading trading, Comparison cmp) {
        List<ExplanationBlock> blocks = new ArrayList<>();
        blocks.add(survey(survey));
        blocks.add(tradingGrade(trading));
        if (trading.available() && (trading.topSector() != null || trading.topTheme() != null)) {
            blocks.add(tradingConcentration(trading));
        }
        if (trading.available()) {
            blocks.add(tradingActivity(trading));
        }
        blocks.add(comparison(survey, trading, cmp));
        return List.copyOf(blocks);
    }

    private static ExplanationBlock survey(Survey s) {
        String fact =
                s.available()
                        ? StockPackageSentence.SURVEY_FACT.render(
                                Map.of(
                                        "type",
                                        s.label(),
                                        "minGrade",
                                        String.valueOf(s.allowedMinGrade())))
                        : StockPackageSentence.SURVEY_FACT_UNKNOWN.render(Map.of());
        return block(
                CheckMetric.SURVEY_PROFILE, StockPackageSentence.SURVEY_DEFINITION, List.of(fact));
    }

    private static ExplanationBlock tradingGrade(Trading t) {
        List<String> facts = new ArrayList<>();
        if (!t.available()) {
            facts.add(
                    StockPackageSentence.TRADING_FACT_INSUFFICIENT.render(
                            Map.of("minBuy", String.valueOf(t.minBuyCount()))));
        } else if (t.avgBuyGrade() == null) {
            facts.add(StockPackageSentence.TRADING_GRADE_FACT_NONE.render(Map.of()));
        } else {
            facts.add(
                    StockPackageSentence.TRADING_GRADE_FACT.render(
                            Map.of("tradeAvg", t.avgBuyGrade().toPlainString())));
            facts.add(
                    StockPackageSentence.TRADING_GRADE_DISTRIBUTION.render(
                            Map.of("distribution", distribution(t.gradeDistribution()))));
        }
        return block(CheckMetric.TRADING_GRADE, StockPackageSentence.TRADING_DEFINITION, facts);
    }

    private static ExplanationBlock tradingConcentration(Trading t) {
        List<String> facts = new ArrayList<>();
        if (t.topSector() != null) {
            facts.add(
                    StockPackageSentence.TRADING_SECTOR_FACT.render(
                            Map.of(
                                    "sector",
                                    t.topSector().name(),
                                    "share",
                                    t.topSector().weight().toPlainString())));
        }
        if (t.topTheme() != null) {
            facts.add(
                    StockPackageSentence.TRADING_THEME_FACT.render(
                            Map.of(
                                    "theme",
                                    t.topTheme().name(),
                                    "share",
                                    t.topTheme().weight().toPlainString())));
        }
        return block(
                CheckMetric.TRADING_CONCENTRATION,
                StockPackageSentence.TRADING_CONCENTRATION_DEFINITION,
                facts);
    }

    private static ExplanationBlock tradingActivity(Trading t) {
        return block(
                CheckMetric.TRADING_ACTIVITY,
                StockPackageSentence.TRADING_ACTIVITY_DEFINITION,
                List.of(
                        StockPackageSentence.TRADING_ACTIVITY_FACT.render(
                                Map.of(
                                        "orderCount",
                                        String.valueOf(t.orderCount()),
                                        "perMonth",
                                        t.ordersPerMonth().toPlainString(),
                                        "sellRatio",
                                        t.sellRatioPercent().toPlainString()))));
    }

    private static ExplanationBlock comparison(Survey s, Trading t, Comparison c) {
        List<String> facts = new ArrayList<>();
        if (!c.available()) {
            facts.add(StockPackageSentence.COMPARISON_UNAVAILABLE.render(Map.of()));
        } else {
            facts.add(
                    StockPackageSentence.COMPARISON_RANGE_FACT.render(
                            Map.of(
                                    "minGrade",
                                    String.valueOf(s.allowedMinGrade()),
                                    "tradeAvg",
                                    t.avgBuyGrade().toPlainString())));
            boolean inRange =
                    t.avgBuyGrade().compareTo(BigDecimal.valueOf(s.allowedMinGrade())) >= 0;
            facts.add(
                    (inRange
                                    ? StockPackageSentence.COMPARISON_IN
                                    : StockPackageSentence.COMPARISON_OUT)
                            .render(Map.of()));
            facts.add(
                    StockPackageSentence.COMPARISON_OUT_SHARE.render(
                            Map.of("share", c.outOfRangeBuySharePercent().toPlainString())));
        }
        return block(CheckMetric.COMPARISON, StockPackageSentence.COMPARISON_DEFINITION, facts);
    }

    private static String distribution(List<GradeShare> shares) {
        return shares.stream()
                .map(g -> g.grade() + "등급 " + g.sharePercent().toPlainString() + "%")
                .collect(Collectors.joining(", "));
    }

    private static ExplanationBlock block(
            CheckMetric metric, StockPackageSentence definition, List<String> facts) {
        return new ExplanationBlock(
                metric, metric.title(), definition.render(Map.of()), List.copyOf(facts));
    }
}
