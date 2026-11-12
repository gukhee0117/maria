package com.app.maria.domain.stockpackage.service;

import com.app.maria.domain.stockpackage.type.CheckMetric;
import com.app.maria.domain.stockpackage.type.StockPackageSentence;
import com.app.maria.domain.stockpackage.vo.StockPackageCheckVO.ExplanationBlock;
import com.app.maria.domain.stockpackage.vo.StockTendencyVO.Survey;
import java.util.List;
import java.util.Map;

/** 설문 성향의 설명을 문장은행으로 조립한다. 문장을 새로 만들지 않고 계산된 값만 끼워 넣는다. */
public final class StockTendencyExplanationBuilder {
    private StockTendencyExplanationBuilder() {}

    public static List<ExplanationBlock> build(Survey survey) {
        return List.of(survey(survey));
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
        return new ExplanationBlock(
                CheckMetric.SURVEY_PROFILE,
                CheckMetric.SURVEY_PROFILE.title(),
                StockPackageSentence.SURVEY_DEFINITION.render(Map.of()),
                List.of(fact));
    }
}
