package com.app.maria.domain.stockpackage.service;

import com.app.maria.domain.stockpackage.type.CheckMetric;
import com.app.maria.domain.stockpackage.type.StockPackageSentence;
import com.app.maria.domain.stockpackage.vo.StockPackageCheckVO.Contribution;
import com.app.maria.domain.stockpackage.vo.StockPackageCheckVO.ExplanationBlock;
import com.app.maria.domain.stockpackage.vo.StockPackageCheckVO.Weighted;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 점검 결과의 설명을 문장은행({@link StockPackageSentence})으로 조립한다. 여기서 하는 일은 어떤 문장을 고를지 정하고 계산된 값을 끼워 넣는 것뿐이다.
 * 문장을 새로 만들어 내지 않는다.
 */
public final class StockPackageExplanationBuilder {
    static final String NO_SECTOR = "업종 없음";
    private static final int SHARE_LIMIT = 3;

    private StockPackageExplanationBuilder() {}

    /** 설명에 쓰는 계산 결과. */
    public record Input(
            BigDecimal avgGrade,
            boolean profileAvailable,
            Integer allowedMinGrade,
            List<String> outNames,
            BigDecimal volatilityPercent,
            BigDecimal hhi,
            BigDecimal equalHhi,
            int itemCount,
            List<Contribution> contributions,
            Weighted topItem,
            List<Weighted> themes,
            List<Weighted> sectors) {}

    public static List<ExplanationBlock> build(Input in) {
        List<ExplanationBlock> blocks = new ArrayList<>();
        blocks.add(avgGrade(in));
        blocks.add(profile(in));
        blocks.add(volatility(in));
        blocks.add(concentration(in));
        blocks.add(topItem(in));
        blocks.add(theme(in));
        in.sectors().stream()
                .filter(s -> !NO_SECTOR.equals(s.name()))
                .findFirst()
                .ifPresent(
                        s ->
                                blocks.add(
                                        block(
                                                CheckMetric.SECTOR,
                                                StockPackageSentence.SECTOR_DEFINITION,
                                                List.of(
                                                        StockPackageSentence.SECTOR_FACT.render(
                                                                Map.of(
                                                                        "sector",
                                                                        s.name(),
                                                                        "weight",
                                                                        percent(s.weight())))))));
        return List.copyOf(blocks);
    }

    private static ExplanationBlock avgGrade(Input in) {
        return block(
                CheckMetric.AVG_GRADE,
                StockPackageSentence.AVG_GRADE_DEFINITION,
                List.of(
                        StockPackageSentence.AVG_GRADE_FACT.render(
                                Map.of("avg", in.avgGrade().toPlainString()))));
    }

    private static ExplanationBlock profile(Input in) {
        List<String> facts = new ArrayList<>();
        if (!in.profileAvailable()) {
            facts.add(StockPackageSentence.PROFILE_FACT_UNKNOWN.render(Map.of()));
        } else {
            if (in.outNames().isEmpty()) {
                facts.add(StockPackageSentence.PROFILE_FACT_NONE.render(Map.of()));
            } else {
                facts.add(
                        StockPackageSentence.PROFILE_FACT_SOME.render(
                                Map.of(
                                        "count",
                                        String.valueOf(in.outNames().size()),
                                        "names",
                                        String.join(", ", in.outNames()))));
            }
            int minGrade = in.allowedMinGrade();
            facts.add(
                    StockPackageSentence.PROFILE_REFERENCE_RANGE.render(
                            Map.of("minGrade", String.valueOf(minGrade))));
            boolean inRange = in.avgGrade().compareTo(BigDecimal.valueOf(minGrade)) >= 0;
            facts.add(
                    (inRange
                                    ? StockPackageSentence.PROFILE_REFERENCE_AVG_IN
                                    : StockPackageSentence.PROFILE_REFERENCE_AVG_OUT)
                            .render(Map.of("avg", in.avgGrade().toPlainString())));
        }
        return block(CheckMetric.PROFILE_RANGE, StockPackageSentence.PROFILE_DEFINITION, facts);
    }

    private static ExplanationBlock volatility(Input in) {
        List<String> facts = new ArrayList<>();
        if (in.volatilityPercent() == null) {
            facts.add(StockPackageSentence.VOLATILITY_FACT_UNAVAILABLE.render(Map.of()));
        } else {
            facts.add(
                    StockPackageSentence.VOLATILITY_FACT.render(
                            Map.of("vol", in.volatilityPercent().toPlainString())));
            facts.add(StockPackageSentence.VOLATILITY_REFERENCE.render(Map.of()));
        }
        return block(CheckMetric.VOLATILITY, StockPackageSentence.VOLATILITY_DEFINITION, facts);
    }

    private static ExplanationBlock concentration(Input in) {
        int compared = in.hhi().compareTo(in.equalHhi());
        StockPackageSentence reference =
                compared > 0
                        ? StockPackageSentence.CONCENTRATION_REFERENCE_HIGHER
                        : compared < 0
                                ? StockPackageSentence.CONCENTRATION_REFERENCE_LOWER
                                : StockPackageSentence.CONCENTRATION_REFERENCE_SAME;
        String shares =
                in.contributions().stream()
                        .sorted(
                                Comparator.comparing(Contribution::hhiSharePercent)
                                        .reversed()
                                        .thenComparing(Contribution::name))
                        .limit(SHARE_LIMIT)
                        .map(c -> c.name() + " " + c.hhiSharePercent().toPlainString() + "%")
                        .collect(Collectors.joining(", "));
        return block(
                CheckMetric.CONCENTRATION,
                StockPackageSentence.CONCENTRATION_DEFINITION,
                List.of(
                        StockPackageSentence.CONCENTRATION_FACT.render(
                                Map.of("hhi", in.hhi().toPlainString())),
                        reference.render(
                                Map.of(
                                        "n",
                                        String.valueOf(in.itemCount()),
                                        "eq",
                                        in.equalHhi().toPlainString())),
                        StockPackageSentence.CONCENTRATION_CONTRIBUTION.render(
                                Map.of("shares", shares))));
    }

    private static ExplanationBlock topItem(Input in) {
        return block(
                CheckMetric.TOP_ITEM,
                StockPackageSentence.TOP_ITEM_DEFINITION,
                List.of(
                        StockPackageSentence.TOP_ITEM_FACT.render(
                                Map.of(
                                        "name",
                                        in.topItem().name(),
                                        "weight",
                                        percent(in.topItem().weight())))));
    }

    private static ExplanationBlock theme(Input in) {
        String fact =
                in.themes().isEmpty()
                        ? StockPackageSentence.THEME_FACT_NONE.render(Map.of())
                        : StockPackageSentence.THEME_FACT.render(
                                Map.of(
                                        "theme",
                                        in.themes().get(0).name(),
                                        "weight",
                                        percent(in.themes().get(0).weight())));
        return block(CheckMetric.THEME, StockPackageSentence.THEME_DEFINITION, List.of(fact));
    }

    private static ExplanationBlock block(
            CheckMetric metric, StockPackageSentence definition, List<String> facts) {
        return new ExplanationBlock(
                metric, metric.title(), definition.render(Map.of()), List.copyOf(facts));
    }

    private static String percent(BigDecimal weight) {
        return weight.stripTrailingZeros().toPlainString();
    }
}
