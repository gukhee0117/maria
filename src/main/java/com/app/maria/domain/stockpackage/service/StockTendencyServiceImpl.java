package com.app.maria.domain.stockpackage.service;

import com.app.maria.domain.investorassessment.dto.response.InvestorAssessmentStatusResponseDTO;
import com.app.maria.domain.investorassessment.service.InvestorAssessmentService;
import com.app.maria.domain.risk.service.RiskGradeService;
import com.app.maria.domain.stockpackage.dto.response.StockTendencyResponseDTO;
import com.app.maria.domain.stockpackage.mapper.StockPackageMapper;
import com.app.maria.domain.stockpackage.mapper.StockTendencyMapper;
import com.app.maria.domain.stockpackage.type.InvestorRiskRange;
import com.app.maria.domain.stockpackage.vo.StockPackageCheckVO.Weighted;
import com.app.maria.domain.stockpackage.vo.StockProductThemeVO;
import com.app.maria.domain.stockpackage.vo.StockTendencyVO;
import com.app.maria.domain.stockpackage.vo.StockTendencyVO.Comparison;
import com.app.maria.domain.stockpackage.vo.StockTendencyVO.GradeShare;
import com.app.maria.domain.stockpackage.vo.StockTendencyVO.Survey;
import com.app.maria.domain.stockpackage.vo.StockTendencyVO.Trading;
import com.app.maria.domain.stockpackage.vo.TradePeriodVO;
import com.app.maria.domain.stockpackage.vo.TradeProductSummaryVO;
import com.app.maria.global.clock.service.BusinessClockService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StockTendencyServiceImpl implements StockTendencyService {
    /** 거래에서 본 경향을 계산하는 데 필요한 체결 매수 건수. 이보다 적으면 계산하지 않는다. */
    public static final int MIN_BUY_COUNT = 5;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final StockTendencyMapper tendencyMapper;
    private final StockPackageMapper stockPackageMapper;
    private final InvestorAssessmentService investorAssessmentService;
    private final RiskGradeService riskGradeService;
    private final BusinessClockService businessClockService;

    @Override
    public StockTendencyResponseDTO getTendency(Long customerId) {
        LocalDateTime now = businessClockService.now();
        InvestorAssessmentStatusResponseDTO status = investorAssessmentService.getMine(customerId);
        Optional<InvestorRiskRange> range =
                status.isAssessed() && !status.isExpired()
                        ? InvestorRiskRange.of(status.getInvestorType())
                        : Optional.empty();
        Survey survey = survey(status, range);

        List<TradeProductSummaryVO> summaries =
                tendencyMapper.selectTradeSummariesByCustomerId(customerId);
        TradePeriodVO period = tendencyMapper.selectTradePeriodByCustomerId(customerId);
        Map<Long, Integer> grades =
                gradeByProduct(
                        summaries.stream()
                                .map(TradeProductSummaryVO::getDomesticProductId)
                                .toList());

        Trading trading = trading(summaries, period, grades, now);
        Comparison comparison = comparison(survey, trading, range, summaries, grades);

        return new StockTendencyResponseDTO(
                StockTendencyVO.builder()
                        .survey(survey)
                        .trading(trading)
                        .comparison(comparison)
                        .explanation(
                                StockTendencyExplanationBuilder.build(survey, trading, comparison))
                        .disclaimer(StockPackageCheckServiceImpl.DISCLAIMER)
                        .checkedAt(now)
                        .build());
    }

    private Survey survey(
            InvestorAssessmentStatusResponseDTO status, Optional<InvestorRiskRange> range) {
        return new Survey(
                range.isPresent(),
                range.isPresent() ? status.getInvestorType().name() : null,
                range.map(InvestorRiskRange::label).orElse(null),
                range.map(InvestorRiskRange::minGrade).orElse(null),
                status.getAssessedAt(),
                status.isExpired());
    }

    private Trading trading(
            List<TradeProductSummaryVO> summaries,
            TradePeriodVO period,
            Map<Long, Integer> grades,
            LocalDateTime now) {
        int buyCount = summaries.stream().mapToInt(TradeProductSummaryVO::getBuyCount).sum();
        int sellCount = summaries.stream().mapToInt(TradeProductSummaryVO::getSellCount).sum();
        BigDecimal totalBuy =
                summaries.stream()
                        .map(TradeProductSummaryVO::getBuyAmount)
                        .filter(a -> a.signum() > 0)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (buyCount < MIN_BUY_COUNT || totalBuy.signum() <= 0) {
            return new Trading(
                    false,
                    MIN_BUY_COUNT,
                    buyCount + sellCount,
                    buyCount,
                    sellCount,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    null,
                    null);
        }

        BigDecimal gradedBuy = BigDecimal.ZERO;
        BigDecimal weightedGrade = BigDecimal.ZERO;
        Map<Integer, BigDecimal> byGrade = new TreeMap<>();
        Map<String, BigDecimal> bySector = new HashMap<>();
        for (TradeProductSummaryVO s : summaries) {
            BigDecimal amount = s.getBuyAmount();
            if (amount.signum() <= 0) {
                continue;
            }
            Integer grade = grades.get(s.getDomesticProductId());
            if (grade != null) {
                gradedBuy = gradedBuy.add(amount);
                weightedGrade = weightedGrade.add(amount.multiply(BigDecimal.valueOf(grade)));
                byGrade.merge(grade, amount, BigDecimal::add);
            }
            if (s.getSectorName() != null && !s.getSectorName().isBlank()) {
                bySector.merge(s.getSectorName(), amount, BigDecimal::add);
            }
        }
        BigDecimal avgGrade = null;
        List<GradeShare> distribution = new ArrayList<>();
        BigDecimal coverage = percent(gradedBuy, totalBuy);
        if (gradedBuy.signum() > 0) {
            avgGrade = weightedGrade.divide(gradedBuy, 2, RoundingMode.DOWN);
            for (Map.Entry<Integer, BigDecimal> e : byGrade.entrySet()) {
                distribution.add(new GradeShare(e.getKey(), percent(e.getValue(), gradedBuy)));
            }
        }

        long months =
                period.getFirstProcessedAt() == null
                        ? 1
                        : Math.max(
                                1,
                                ChronoUnit.MONTHS.between(
                                                period.getFirstProcessedAt()
                                                        .toLocalDate()
                                                        .withDayOfMonth(1),
                                                now.toLocalDate().withDayOfMonth(1))
                                        + 1);
        int orderCount = buyCount + sellCount;
        BigDecimal perMonth =
                BigDecimal.valueOf(orderCount)
                        .divide(BigDecimal.valueOf(months), 1, RoundingMode.HALF_UP);
        BigDecimal sellRatio =
                percent(BigDecimal.valueOf(sellCount), BigDecimal.valueOf(orderCount));

        return new Trading(
                true,
                MIN_BUY_COUNT,
                orderCount,
                buyCount,
                sellCount,
                perMonth,
                sellRatio,
                avgGrade,
                coverage,
                distribution,
                top(bySector, totalBuy),
                top(themeAmounts(summaries), totalBuy));
    }

    private Comparison comparison(
            Survey survey,
            Trading trading,
            Optional<InvestorRiskRange> range,
            List<TradeProductSummaryVO> summaries,
            Map<Long, Integer> grades) {
        if (!survey.available() || !trading.available() || trading.avgBuyGrade() == null) {
            return new Comparison(false, null);
        }
        BigDecimal graded = BigDecimal.ZERO;
        BigDecimal outOfRange = BigDecimal.ZERO;
        for (TradeProductSummaryVO s : summaries) {
            Integer grade = grades.get(s.getDomesticProductId());
            if (grade == null || s.getBuyAmount().signum() <= 0) {
                continue;
            }
            graded = graded.add(s.getBuyAmount());
            if (!range.orElseThrow().allows(grade)) {
                outOfRange = outOfRange.add(s.getBuyAmount());
            }
        }
        return new Comparison(true, percent(outOfRange, graded));
    }

    /** 종목별 매수 금액을 소속 테마에 더한다. 한 종목이 여러 테마에 속하면 각각에 더해 합이 전체를 넘을 수 있다. */
    private Map<String, BigDecimal> themeAmounts(List<TradeProductSummaryVO> summaries) {
        List<Long> productIds =
                summaries.stream()
                        .filter(s -> s.getBuyAmount().signum() > 0)
                        .map(TradeProductSummaryVO::getDomesticProductId)
                        .toList();
        Map<Long, BigDecimal> amountByProduct =
                summaries.stream()
                        .collect(
                                Collectors.toMap(
                                        TradeProductSummaryVO::getDomesticProductId,
                                        TradeProductSummaryVO::getBuyAmount));
        Map<String, BigDecimal> byTheme = new HashMap<>();
        if (productIds.isEmpty()) {
            return byTheme;
        }
        for (StockProductThemeVO theme : stockPackageMapper.selectThemesByProductIds(productIds)) {
            BigDecimal amount = amountByProduct.get(theme.getDomesticProductId());
            if (amount != null && amount.signum() > 0) {
                byTheme.merge(theme.getThemeName(), amount, BigDecimal::add);
            }
        }
        return byTheme;
    }

    /** 금액이 가장 큰 항목과 전체 매수 금액 대비 비율(%). 같으면 이름순. 항목이 없으면 null. */
    private static Weighted top(Map<String, BigDecimal> amounts, BigDecimal total) {
        return amounts.entrySet().stream()
                .max(
                        Map.Entry.<String, BigDecimal>comparingByValue()
                                .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                .map(e -> new Weighted(e.getKey(), percent(e.getValue(), total)))
                .orElse(null);
    }

    private static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        return part.multiply(HUNDRED).divide(whole, 1, RoundingMode.HALF_UP);
    }

    private Map<Long, Integer> gradeByProduct(java.util.Collection<Long> productIds) {
        return riskGradeService.findUsableGrades(productIds);
    }
}
