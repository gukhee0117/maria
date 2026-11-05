package com.app.maria.domain.stockpackage.service;

import com.app.maria.domain.investorassessment.dto.response.InvestorAssessmentStatusResponseDTO;
import com.app.maria.domain.investorassessment.service.InvestorAssessmentService;
import com.app.maria.domain.risk.service.RiskGradeService;
import com.app.maria.domain.stockpackage.dto.request.StockPackageCheckRequestDTO;
import com.app.maria.domain.stockpackage.dto.request.StockPackageCheckRequestDTO.Item;
import com.app.maria.domain.stockpackage.dto.response.StockPackageCheckResponseDTO;
import com.app.maria.domain.stockpackage.mapper.StockPackageMapper;
import com.app.maria.domain.stockpackage.type.InvestorRiskRange;
import com.app.maria.domain.stockpackage.vo.StockPackageCheckVO;
import com.app.maria.domain.stockpackage.vo.StockPackageCheckVO.Contribution;
import com.app.maria.domain.stockpackage.vo.StockPackageCheckVO.Weighted;
import com.app.maria.domain.stockpackage.vo.StockProductThemeVO;
import com.app.maria.domain.stockpackage.vo.StockProductVO;
import com.app.maria.global.client.risk.PythonPackageRiskClient;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 패키지 점검. 외부 호출(risk)이 있어 트랜잭션을 걸지 않고, DB 조회는 각각 읽기만 한다. */
@Service
@RequiredArgsConstructor
@Slf4j
public class StockPackageCheckServiceImpl implements StockPackageCheckService {
    static final String DISCLAIMER = "투자 권유가 아닌 정보 제공이에요";
    static final String NO_SECTOR = StockPackageExplanationBuilder.NO_SECTOR;
    static final String VOLATILITY = "volatility";

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal TEN_THOUSAND = BigDecimal.valueOf(10_000);

    private final StockPackageMapper stockPackageMapper;
    private final InvestorAssessmentService investorAssessmentService;
    private final RiskGradeService riskGradeService;
    private final PythonPackageRiskClient packageRiskClient;
    private final BusinessClockService businessClockService;

    @Override
    public StockPackageCheckResponseDTO check(
            Long customerId, StockPackageCheckRequestDTO request) {
        List<Item> items = request.items();
        List<Long> productIds = items.stream().map(Item::domesticProductId).toList();
        if (new HashSet<>(productIds).size() != productIds.size()) {
            throw new AppException(ErrorType.STOCK_PACKAGE_ITEM_DUPLICATED);
        }
        BigDecimal weightSum =
                items.stream().map(Item::weight).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (weightSum.compareTo(HUNDRED) != 0) {
            throw new AppException(ErrorType.STOCK_PACKAGE_WEIGHT_SUM_INVALID, weightSum);
        }

        Map<Long, StockProductVO> products =
                stockPackageMapper.selectProductsByIds(productIds).stream()
                        .collect(Collectors.toMap(StockProductVO::getDomesticProductId, p -> p));
        Map<Long, Integer> grades = gradeByProduct(productIds);
        for (Long productId : productIds) {
            if (!products.containsKey(productId)) {
                throw new AppException(ErrorType.DOMESTIC_PRODUCT_NOT_FOUND, productId);
            }
            if (!grades.containsKey(productId)) {
                throw new AppException(ErrorType.STOCK_PACKAGE_GRADE_UNAVAILABLE, productId);
            }
        }
        Optional<InvestorRiskRange> range = currentRange(customerId);

        BigDecimal avgGrade = BigDecimal.ZERO;
        BigDecimal sumSquare = BigDecimal.ZERO;
        for (Item item : items) {
            avgGrade =
                    avgGrade.add(
                            BigDecimal.valueOf(grades.get(item.domesticProductId()))
                                    .multiply(item.weight()));
            sumSquare = sumSquare.add(item.weight().multiply(item.weight()));
        }
        avgGrade = avgGrade.divide(HUNDRED, 2, RoundingMode.DOWN);

        List<Contribution> contributions = new ArrayList<>();
        List<String> outNames = new ArrayList<>();
        Item top = items.get(0);
        for (Item item : items) {
            StockProductVO product = products.get(item.domesticProductId());
            int grade = grades.get(item.domesticProductId());
            Boolean out = range.map(r -> !r.allows(grade)).orElse(null);
            if (Boolean.TRUE.equals(out)) {
                outNames.add(product.getName());
            }
            BigDecimal share =
                    item.weight()
                            .multiply(item.weight())
                            .multiply(HUNDRED)
                            .divide(sumSquare, 1, RoundingMode.HALF_UP);
            contributions.add(
                    new Contribution(
                            item.domesticProductId(),
                            product.getName(),
                            item.weight(),
                            share,
                            grade,
                            out));
            if (item.weight().compareTo(top.weight()) > 0) {
                top = item;
            }
        }
        Weighted topItem =
                new Weighted(products.get(top.domesticProductId()).getName(), top.weight());

        List<Weighted> themes = themeWeights(items, productIds);
        List<Weighted> sectors = sectorWeights(items, products);

        List<String> unavailable = new ArrayList<>();
        BigDecimal volatilityPercent = null;
        java.time.OffsetDateTime volatilityAsOf = null;
        try {
            List<PythonPackageRiskClient.Item> riskItems =
                    items.stream()
                            .map(
                                    i ->
                                            new PythonPackageRiskClient.Item(
                                                    products.get(i.domesticProductId()).getTicker(),
                                                    i.weight()
                                                            .divide(
                                                                    HUNDRED,
                                                                    4,
                                                                    RoundingMode.HALF_UP)))
                            .toList();
            var volatility = packageRiskClient.fetchVolatility(riskItems);
            volatilityPercent =
                    volatility.volatility().multiply(HUNDRED).setScale(1, RoundingMode.HALF_UP);
            volatilityAsOf = volatility.asOf();
        } catch (AppException e) {
            // 변동성만 계산 불가로 두고 나머지 점검은 그대로 돌려준다. 응답 본문은 남기지 않는다.
            unavailable.add(VOLATILITY);
            log.warn("STOCK_PACKAGE_CHECK_VOLATILITY_UNAVAILABLE code={}", e.getErrorType().name());
        }

        BigDecimal hhi = sumSquare.setScale(0, RoundingMode.HALF_UP);
        BigDecimal equalHhi =
                TEN_THOUSAND.divide(BigDecimal.valueOf(items.size()), 0, RoundingMode.HALF_UP);
        Integer outCount = range.isPresent() ? outNames.size() : null;

        Integer allowedMinGrade = range.map(InvestorRiskRange::minGrade).orElse(null);
        // 고객에게 보이는 설명은 사전에 검수한 문장은행에서 고른 문장에 계산한 값만 끼워 만든다.
        var explanation =
                StockPackageExplanationBuilder.build(
                        new StockPackageExplanationBuilder.Input(
                                avgGrade,
                                range.isPresent(),
                                allowedMinGrade,
                                outNames,
                                volatilityPercent,
                                hhi,
                                equalHhi,
                                items.size(),
                                contributions,
                                topItem,
                                themes,
                                sectors));

        return new StockPackageCheckResponseDTO(
                StockPackageCheckVO.builder()
                        .itemCount(items.size())
                        .avgRiskGrade(avgGrade)
                        .profileAvailable(range.isPresent())
                        .outOfProfileCount(outCount)
                        .outOfProfileNames(range.isPresent() ? outNames : null)
                        .volatilityPercent(volatilityPercent)
                        .volatilityAsOf(volatilityAsOf)
                        .hhi(hhi)
                        .equalWeightHhi(equalHhi)
                        .topItem(topItem)
                        .contributions(contributions)
                        .themes(themes)
                        .sectors(sectors)
                        .unavailableFields(unavailable)
                        .allowedMinGrade(allowedMinGrade)
                        .explanation(explanation)
                        .disclaimer(DISCLAIMER)
                        .checkedAt(businessClockService.now())
                        .build());
    }

    private List<Weighted> themeWeights(List<Item> items, List<Long> productIds) {
        Map<Long, BigDecimal> weightByProduct =
                items.stream().collect(Collectors.toMap(Item::domesticProductId, Item::weight));
        Map<String, BigDecimal> byTheme = new LinkedHashMap<>();
        for (StockProductThemeVO theme : stockPackageMapper.selectThemesByProductIds(productIds)) {
            byTheme.merge(
                    theme.getThemeName(),
                    weightByProduct.get(theme.getDomesticProductId()),
                    BigDecimal::add);
        }
        return sorted(byTheme);
    }

    private List<Weighted> sectorWeights(List<Item> items, Map<Long, StockProductVO> products) {
        Map<String, BigDecimal> bySector = new LinkedHashMap<>();
        for (Item item : items) {
            String sector = products.get(item.domesticProductId()).getSectorName();
            bySector.merge(
                    sector == null || sector.isBlank() ? NO_SECTOR : sector,
                    item.weight(),
                    BigDecimal::add);
        }
        return sorted(bySector);
    }

    private List<Weighted> sorted(Map<String, BigDecimal> weights) {
        return weights.entrySet().stream()
                .map(e -> new Weighted(e.getKey(), e.getValue()))
                .sorted(
                        Comparator.comparing(Weighted::weight)
                                .reversed()
                                .thenComparing(Weighted::name))
                .toList();
    }

    private Optional<InvestorRiskRange> currentRange(Long customerId) {
        InvestorAssessmentStatusResponseDTO status = investorAssessmentService.getMine(customerId);
        if (!status.isAssessed() || status.isExpired()) {
            return Optional.empty();
        }
        return InvestorRiskRange.of(status.getInvestorType());
    }

    private Map<Long, Integer> gradeByProduct(java.util.Collection<Long> productIds) {
        return riskGradeService.findUsableGrades(productIds);
    }
}
