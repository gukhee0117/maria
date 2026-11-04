package com.app.maria.domain.stockpackage.dto.response;

import com.app.maria.domain.stockpackage.dto.response.StockPackageCheckResponseDTO.ExplanationBlockResponse;
import com.app.maria.domain.stockpackage.dto.response.StockPackageCheckResponseDTO.WeightedResponse;
import com.app.maria.domain.stockpackage.vo.StockTendencyVO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;

/** 설문 투자성향과 거래에서 본 경향. 거래에서 본 경향은 참고 정보이고 공식 투자성향이 아니다. */
@Getter
public class StockTendencyResponseDTO {
    private final SurveyResponse survey;
    private final TradingResponse trading;
    private final ComparisonResponse comparison;
    private final List<ExplanationBlockResponse> explanation;
    private final String disclaimer;
    private final LocalDateTime checkedAt;

    public StockTendencyResponseDTO(StockTendencyVO vo) {
        this.survey = new SurveyResponse(vo.getSurvey());
        this.trading = new TradingResponse(vo.getTrading());
        this.comparison = new ComparisonResponse(vo.getComparison());
        this.explanation = vo.getExplanation().stream().map(ExplanationBlockResponse::new).toList();
        this.disclaimer = vo.getDisclaimer();
        this.checkedAt = vo.getCheckedAt();
    }

    @Getter
    public static class SurveyResponse {
        private final boolean available;
        private final String investorType;
        private final String label;
        private final Integer allowedMinGrade;
        private final LocalDateTime assessedAt;
        private final boolean expired;

        public SurveyResponse(StockTendencyVO.Survey vo) {
            this.available = vo.available();
            this.investorType = vo.investorType();
            this.label = vo.label();
            this.allowedMinGrade = vo.allowedMinGrade();
            this.assessedAt = vo.assessedAt();
            this.expired = vo.expired();
        }
    }

    @Getter
    public static class GradeShareResponse {
        private final int grade;
        private final BigDecimal sharePercent;

        public GradeShareResponse(StockTendencyVO.GradeShare vo) {
            this.grade = vo.grade();
            this.sharePercent = vo.sharePercent();
        }
    }

    @Getter
    public static class TradingResponse {
        private final boolean available;
        private final int minBuyCount;
        private final int orderCount;
        private final int buyCount;
        private final int sellCount;
        private final BigDecimal ordersPerMonth;
        private final BigDecimal sellRatioPercent;
        private final BigDecimal avgBuyGrade;
        private final BigDecimal gradeCoveragePercent;
        private final List<GradeShareResponse> gradeDistribution;
        private final WeightedResponse topSector;
        private final WeightedResponse topTheme;

        public TradingResponse(StockTendencyVO.Trading vo) {
            this.available = vo.available();
            this.minBuyCount = vo.minBuyCount();
            this.orderCount = vo.orderCount();
            this.buyCount = vo.buyCount();
            this.sellCount = vo.sellCount();
            this.ordersPerMonth = vo.ordersPerMonth();
            this.sellRatioPercent = vo.sellRatioPercent();
            this.avgBuyGrade = vo.avgBuyGrade();
            this.gradeCoveragePercent = vo.gradeCoveragePercent();
            this.gradeDistribution =
                    vo.gradeDistribution().stream().map(GradeShareResponse::new).toList();
            this.topSector = vo.topSector() == null ? null : new WeightedResponse(vo.topSector());
            this.topTheme = vo.topTheme() == null ? null : new WeightedResponse(vo.topTheme());
        }
    }

    @Getter
    public static class ComparisonResponse {
        private final boolean available;
        private final BigDecimal outOfRangeBuySharePercent;

        public ComparisonResponse(StockTendencyVO.Comparison vo) {
            this.available = vo.available();
            this.outOfRangeBuySharePercent = vo.outOfRangeBuySharePercent();
        }
    }
}
