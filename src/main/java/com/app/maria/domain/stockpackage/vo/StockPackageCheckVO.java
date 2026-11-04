package com.app.maria.domain.stockpackage.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * 서비스가 계산한 패키지 점검 결과. 모든 숫자는 코드가 계산한 값이고, 설명 문장은 이 숫자로 만든 고정 템플릿이다. nullable 값은 계산할 수 없다는 뜻이며
 * unavailableFields에 이름이 들어 있다.
 */
@Getter
@Builder
public class StockPackageCheckVO {
    private final int itemCount;

    /** 구성 종목 위험등급의 비중 가중평균. 1(매우 높은 위험)~6(매우 낮은 위험). */
    private final BigDecimal avgRiskGrade;

    /** 유효한 투자성향 진단이 있어 성향 범위를 판단할 수 있는가. */
    private final boolean profileAvailable;

    /** 성향 범위 밖 종목 수와 이름. 성향을 판단할 수 없으면 null. */
    private final Integer outOfProfileCount;

    private final List<String> outOfProfileNames;

    /** 연 변동성(%). risk를 쓸 수 없으면 null. */
    private final BigDecimal volatilityPercent;

    private final OffsetDateTime volatilityAsOf;

    /** 종목 집중도(허핀달, 0~10,000)와 같은 종목 수를 균등 비중으로 담았을 때의 값. */
    private final BigDecimal hhi;

    private final BigDecimal equalWeightHhi;

    private final Weighted topItem;
    private final List<Contribution> contributions;
    private final List<Weighted> themes;
    private final List<Weighted> sectors;
    private final List<String> unavailableFields;

    /** 내 성향이 허용하는 가장 높은 위험등급. 성향을 판단할 수 없으면 null. */
    private final Integer allowedMinGrade;

    /** 문장은행으로 조립한 설명. 지표마다 정의 한 문장과 사실 문장들로 이루어진다. */
    private final List<ExplanationBlock> explanation;

    private final String disclaimer;
    private final LocalDateTime checkedAt;

    /** 지표 하나의 설명. definition은 지표의 뜻, facts는 이 패키지의 값과 기준선이다. */
    public record ExplanationBlock(
            com.app.maria.domain.stockpackage.type.CheckMetric metric,
            String title,
            String definition,
            List<String> facts) {}

    /** 이름과 비중(%). 테마는 한 종목이 여러 테마에 속해 합이 100을 넘을 수 있다. */
    public record Weighted(String name, BigDecimal weight) {}

    /** 종목별 값. hhiSharePercent는 전체 집중도 중 이 종목이 차지하는 몫(%)이다. */
    public record Contribution(
            Long domesticProductId,
            String name,
            BigDecimal weight,
            BigDecimal hhiSharePercent,
            Integer riskGrade,
            Boolean outOfProfile) {}
}
