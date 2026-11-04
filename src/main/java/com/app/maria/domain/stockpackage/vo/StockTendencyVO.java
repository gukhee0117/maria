package com.app.maria.domain.stockpackage.vo;

import com.app.maria.domain.stockpackage.vo.StockPackageCheckVO.ExplanationBlock;
import com.app.maria.domain.stockpackage.vo.StockPackageCheckVO.Weighted;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/** 설문 투자성향과 거래에서 본 경향. 거래에서 본 경향은 성향 이름표를 붙이지 않고 계산한 사실만 담는다. 모든 숫자는 코드가 계산하고 설명은 문장은행으로 만든다. */
@Getter
@Builder
public class StockTendencyVO {
    private final Survey survey;
    private final Trading trading;
    private final Comparison comparison;
    private final List<ExplanationBlock> explanation;
    private final String disclaimer;
    private final LocalDateTime checkedAt;

    /** 설문 성향. available이 false면 진단이 없거나 만료된 것이다. */
    public record Survey(
            boolean available,
            String investorType,
            String label,
            Integer allowedMinGrade,
            LocalDateTime assessedAt,
            boolean expired) {}

    public record GradeShare(int grade, BigDecimal sharePercent) {}

    /**
     * 체결된 RIA 국내 주문으로 본 경향. available이 false면 매수가 minBuyCount건보다 적어 계산하지 않은 것이고, 이때 값들은 건수를 뺀 나머지가
     * null이다. topSector·topTheme의 weight는 전체 매수 금액 중 비율(%)이다.
     */
    public record Trading(
            boolean available,
            int minBuyCount,
            int orderCount,
            int buyCount,
            int sellCount,
            BigDecimal ordersPerMonth,
            BigDecimal sellRatioPercent,
            BigDecimal avgBuyGrade,
            BigDecimal gradeCoveragePercent,
            List<GradeShare> gradeDistribution,
            Weighted topSector,
            Weighted topTheme) {}

    /** 두 결과를 같은 등급 기준으로 비교한 값. 한쪽이라도 없으면 available이 false다. */
    public record Comparison(boolean available, BigDecimal outOfRangeBuySharePercent) {}
}
