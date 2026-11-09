package com.app.maria.domain.fundflow.service;

import static com.app.maria.domain.fundflow.service.FundFlowRatios.RATIO_SCALE;
import static com.app.maria.domain.fundflow.service.FundFlowRatios.ratio;

import com.app.maria.domain.fundflow.dto.FundFlowAccountFlowRowDTO;
import com.app.maria.domain.fundflow.dto.FundFlowAgeMedianDTO;
import com.app.maria.domain.fundflow.dto.FundFlowAllocationDTO;
import com.app.maria.domain.fundflow.dto.FundFlowBucketDTO;
import com.app.maria.domain.fundflow.dto.FundFlowConcentrationDTO;
import com.app.maria.domain.fundflow.dto.FundFlowFilterDTO;
import com.app.maria.domain.fundflow.dto.FundFlowFunnelDTO;
import com.app.maria.domain.fundflow.dto.FundFlowLeakageCategoryDTO;
import com.app.maria.domain.fundflow.dto.FundFlowLeakageDTO;
import com.app.maria.domain.fundflow.dto.FundFlowLorenzPointDTO;
import com.app.maria.domain.fundflow.dto.FundFlowMonthDTO;
import com.app.maria.domain.fundflow.dto.FundFlowMonthlyDTO;
import com.app.maria.domain.fundflow.dto.FundFlowOverviewDTO;
import com.app.maria.domain.fundflow.dto.FundFlowProductCostDTO;
import com.app.maria.domain.fundflow.dto.FundFlowRatioBinDTO;
import com.app.maria.domain.fundflow.dto.FundFlowReliefDTO;
import com.app.maria.domain.fundflow.dto.FundFlowRetentionDTO;
import com.app.maria.domain.fundflow.dto.FundFlowRetentionLotRowDTO;
import com.app.maria.domain.fundflow.dto.FundFlowRetentionPointDTO;
import com.app.maria.domain.fundflow.dto.FundFlowSegmentCellDTO;
import com.app.maria.domain.fundflow.dto.FundFlowSegmentDTO;
import com.app.maria.domain.fundflow.dto.FundFlowSegmentRowDTO;
import com.app.maria.domain.fundflow.dto.request.FundFlowMonthlyRequestDTO;
import com.app.maria.domain.fundflow.dto.request.FundFlowPeriodRequestDTO;
import com.app.maria.domain.fundflow.dto.request.FundFlowReliefRequestDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowAllocationResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowConcentrationResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowFunnelResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowLeakageResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowMonthlyResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowOverviewResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowReliefResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowRetentionResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowSegmentResponseDTO;
import com.app.maria.domain.fundflow.mapper.FundFlowMapper;
import com.app.maria.domain.fundflow.type.AdjustRatioBin;
import com.app.maria.domain.fundflow.type.FundFlowPeriod;
import com.app.maria.domain.fundflow.type.LeakageCategory;
import com.app.maria.domain.fundflow.type.ReliefScope;
import com.app.maria.global.clock.service.BusinessClockService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminFundFlowServiceImpl implements AdminFundFlowService {

    /** 개인 식별 방지: 이 인원 미만 칸의 합계·비율은 내려보내지 않는다. */
    private static final long MIN_CELL_ACCOUNTS = 5;

    private static final List<String> AGE_BANDS = List.of("20대 이하", "30대", "40대", "50대", "60대 이상");
    private static final String FUND_BUCKET = "ETF·펀드";
    private static final String FUND_TYPE = "FUND";
    private static final String OTHER_SECTOR = "기타";
    private static final int INVEST_PER_RELIEF_SCALE = 1;
    private static final int INTERNAL_SCALE = 10;
    private static final int LORENZ_STEP_PERCENT = 5;
    private static final BigDecimal TOP_SHARE = new BigDecimal("0.10");
    private static final int RETENTION_STEP_DAYS = 10;

    private final FundFlowMapper fundFlowMapper;
    private final BusinessClockService businessClockService;

    @Override
    public FundFlowFunnelResponseDTO getFunnel(FundFlowPeriodRequestDTO request) {
        FundFlowFilterDTO filter = buildFilter(request.getPeriod());
        return new FundFlowFunnelResponseDTO(
                FundFlowFunnelDTO.builder()
                        .asOfDate(filter.getAsOfDate())
                        .stages(fundFlowMapper.selectFunnel(filter))
                        .build());
    }

    @Override
    public FundFlowOverviewResponseDTO getOverview(FundFlowPeriodRequestDTO request) {
        FundFlowFilterDTO filter = buildFilter(request.getPeriod());
        FundFlowOverviewDTO overview = fundFlowMapper.selectOverview(filter);
        overview.setAsOfDate(filter.getAsOfDate());
        overview.setReinvestRatio(ratio(overview.getDomesticBuyTotal(), overview.getInflowTotal()));
        overview.setNetInvestRatio(
                ratio(
                        overview.getDomesticBuyTotal().subtract(overview.getDomesticSellTotal()),
                        overview.getInflowTotal()));
        return new FundFlowOverviewResponseDTO(overview);
    }

    @Override
    public FundFlowLeakageResponseDTO getLeakage() {
        FundFlowFilterDTO filter = buildFilter(FundFlowPeriod.ALL);
        Map<String, FundFlowLeakageCategoryDTO> byCode =
                fundFlowMapper.selectLeakage().stream()
                        .collect(Collectors.toMap(FundFlowLeakageCategoryDTO::getCode, c -> c));
        long total =
                byCode.values().stream().mapToLong(FundFlowLeakageCategoryDTO::getAccounts).sum();

        List<FundFlowLeakageCategoryDTO> categories = new ArrayList<>();
        for (LeakageCategory category : LeakageCategory.values()) {
            FundFlowLeakageCategoryDTO row = byCode.get(category.name());
            long accounts = row == null ? 0L : row.getAccounts();
            categories.add(
                    FundFlowLeakageCategoryDTO.builder()
                            .code(category.name())
                            .label(category.getLabel())
                            .accounts(accounts)
                            .share(ratio(BigDecimal.valueOf(accounts), BigDecimal.valueOf(total)))
                            .withExternalBuy(row == null ? 0L : row.getWithExternalBuy())
                            .build());
        }
        return new FundFlowLeakageResponseDTO(
                FundFlowLeakageDTO.builder()
                        .asOfDate(filter.getAsOfDate())
                        .snapshotCalculatedAt(fundFlowMapper.selectSnapshotCalculatedAt())
                        .total(total)
                        .categories(categories)
                        .ratioBins(buildRatioBins())
                        .build());
    }

    @Override
    public FundFlowMonthlyResponseDTO getMonthly(FundFlowMonthlyRequestDTO request) {
        FundFlowFilterDTO filter = buildFilter(request.getPeriod());
        if (request.getFromMonth() != null) {
            filter.setFromMonth(request.getFromMonth().atDay(1));
        } else if (filter.getPeriodFrom() != null) {
            filter.setFromMonth(filter.getPeriodFrom());
        }
        if (request.getToMonth() != null) {
            filter.setToMonth(request.getToMonth().atDay(1));
        }

        List<FundFlowMonthDTO> months = new ArrayList<>(fundFlowMapper.selectMonthlyClosed(filter));
        if (includesPartialMonth(filter)) {
            FundFlowMonthDTO partial = fundFlowMapper.selectMonthlyPartial(filter);
            partial.setFlowMonth(filter.getMonthStart());
            partial.setPartial(true);
            months.add(partial);
        }
        return new FundFlowMonthlyResponseDTO(
                FundFlowMonthlyDTO.builder().asOfDate(filter.getAsOfDate()).months(months).build());
    }

    @Override
    public FundFlowSegmentResponseDTO getSegments(FundFlowPeriodRequestDTO request) {
        FundFlowFilterDTO filter = buildFilter(request.getPeriod());
        List<FundFlowSegmentRowDTO> rows = fundFlowMapper.selectSegments(filter);

        List<FundFlowSegmentCellDTO> cells = new ArrayList<>();
        for (String ageBand : AGE_BANDS) {
            List<FundFlowSegmentRowDTO> bandRows =
                    rows.stream()
                            .filter(r -> ageBand.equals(r.getAgeBand()))
                            .sorted(Comparator.comparing(FundFlowSegmentRowDTO::getInvestorType))
                            .toList();
            if (bandRows.isEmpty()) {
                continue;
            }
            bandRows.forEach(r -> cells.add(toCell(r)));
            cells.add(toCell(sum(ageBand, null, bandRows)));
        }
        cells.add(toCell(sum(null, null, rows)));
        return new FundFlowSegmentResponseDTO(
                FundFlowSegmentDTO.builder()
                        .asOfDate(filter.getAsOfDate())
                        .cells(cells)
                        .ageMedians(toAgeMedians(fundFlowMapper.selectAccountFlows(filter)))
                        .build());
    }

    @Override
    public FundFlowReliefResponseDTO getRelief(FundFlowReliefRequestDTO request) {
        ReliefScope scope = request.getScope() == null ? ReliefScope.ALL : request.getScope();
        FundFlowFilterDTO filter = buildFilter(FundFlowPeriod.ALL);
        filter.setScope(scope.name());

        FundFlowReliefDTO relief = fundFlowMapper.selectRelief(filter);
        relief.setAsOfDate(filter.getAsOfDate());
        relief.setScope(scope.name());
        if (relief.getReliefTotal().signum() > 0) {
            relief.setInvestPerRelief(
                    relief.getNetInvestTotal()
                            .divide(
                                    relief.getReliefTotal(),
                                    INVEST_PER_RELIEF_SCALE,
                                    RoundingMode.HALF_UP));
        }
        return new FundFlowReliefResponseDTO(relief);
    }

    @Override
    public FundFlowAllocationResponseDTO getAllocation(FundFlowPeriodRequestDTO request) {
        FundFlowFilterDTO filter = buildFilter(request.getPeriod());
        List<FundFlowProductCostDTO> products = fundFlowMapper.selectProductCosts();
        BigDecimal total =
                products.stream()
                        .map(FundFlowProductCostDTO::getPurchaseCost)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, BigDecimal> costByBucket = new LinkedHashMap<>();
        for (FundFlowProductCostDTO product : products) {
            costByBucket.merge(bucketLabel(product), product.getPurchaseCost(), BigDecimal::add);
        }
        List<FundFlowBucketDTO> buckets =
                costByBucket.entrySet().stream()
                        .map(
                                e ->
                                        FundFlowBucketDTO.builder()
                                                .label(e.getKey())
                                                .purchaseCost(e.getValue())
                                                .share(ratio(e.getValue(), total))
                                                .build())
                        .sorted(Comparator.comparing(FundFlowBucketDTO::getPurchaseCost).reversed())
                        .toList();

        return new FundFlowAllocationResponseDTO(
                FundFlowAllocationDTO.builder()
                        .asOfDate(filter.getAsOfDate())
                        .totalCost(total)
                        .buckets(buckets)
                        .hhiBucket(hhi(costByBucket.values(), total))
                        .hhiProduct(
                                hhi(
                                        products.stream()
                                                .map(FundFlowProductCostDTO::getPurchaseCost)
                                                .toList(),
                                        total))
                        .topNetBuy(fundFlowMapper.selectTopNetBuy(filter))
                        .build());
    }

    @Override
    public FundFlowConcentrationResponseDTO getConcentration() {
        FundFlowFilterDTO filter = buildFilter(FundFlowPeriod.ALL);
        List<BigDecimal> reliefs = fundFlowMapper.selectReliefAmounts(filter);
        List<FundFlowLorenzPointDTO> lorenz =
                FundFlowStatistics.lorenz(reliefs, LORENZ_STEP_PERCENT).stream()
                        .map(
                                p ->
                                        FundFlowLorenzPointDTO.builder()
                                                .populationShare(p[0])
                                                .valueShare(p[1])
                                                .build())
                        .toList();
        return new FundFlowConcentrationResponseDTO(
                FundFlowConcentrationDTO.builder()
                        .asOfDate(filter.getAsOfDate())
                        .accounts((long) reliefs.size())
                        .reliefTotal(FundFlowStatistics.sum(reliefs))
                        .top10Share(FundFlowStatistics.topShare(reliefs, TOP_SHARE))
                        .gini(FundFlowStatistics.gini(reliefs))
                        .lorenz(lorenz)
                        .build());
    }

    @Override
    public FundFlowRetentionResponseDTO getRetention(FundFlowPeriodRequestDTO request) {
        FundFlowFilterDTO filter = buildFilter(request.getPeriod());
        List<FundFlowRetentionLotRowDTO> lots = fundFlowMapper.selectRetentionLots(filter);

        int n = lots.size();
        int[] accountIdx = new int[n];
        int[] days = new int[n];
        boolean[] events = new boolean[n];
        double[] weights = new double[n];
        long eventCount = 0;
        int accountCount = 0;
        Long prevAccount = null;
        for (int i = 0; i < n; i++) {
            FundFlowRetentionLotRowDTO lot = lots.get(i);
            if (!lot.getAccountId().equals(prevAccount)) {
                accountCount++;
                prevAccount = lot.getAccountId();
            }
            accountIdx[i] = accountCount;
            days[i] = Math.max(0, lot.getDays());
            events[i] = Boolean.TRUE.equals(lot.getEvent());
            weights[i] = lot.getFinalAmount().doubleValue();
            if (events[i]) {
                eventCount++;
            }
        }

        FundFlowKaplanMeier.Result result =
                FundFlowKaplanMeier.compute(
                        new FundFlowKaplanMeier.Input(accountIdx, days, events, weights),
                        RETENTION_STEP_DAYS);
        List<FundFlowRetentionPointDTO> points = new ArrayList<>();
        for (int g = 0; g < result.grid().length; g++) {
            points.add(
                    FundFlowRetentionPointDTO.builder()
                            .day(result.grid()[g])
                            .survival(toRatio(result.survival()[g]))
                            .lower(toRatio(result.lower()[g]))
                            .upper(toRatio(result.upper()[g]))
                            .weightedSurvival(toRatio(result.weightedSurvival()[g]))
                            .atRisk((long) result.atRisk()[g])
                            .build());
        }
        return new FundFlowRetentionResponseDTO(
                FundFlowRetentionDTO.builder()
                        .asOfDate(filter.getAsOfDate())
                        .lots((long) n)
                        .accounts((long) accountCount)
                        .events(eventCount)
                        .maxObservedDays(result.grid()[result.grid().length - 1])
                        .bootstrapReplicates(n == 0 ? 0 : FundFlowKaplanMeier.REPLICATES)
                        .points(points)
                        .build());
    }

    private BigDecimal toRatio(double value) {
        return BigDecimal.valueOf(value).setScale(RATIO_SCALE, RoundingMode.HALF_UP);
    }

    /** 매도한 계좌의 조정비율 구간 분포. 구간이 비어 있어도 모든 구간을 순서대로 낸다. */
    private List<FundFlowRatioBinDTO> buildRatioBins() {
        Map<String, Long> byCode =
                fundFlowMapper.selectRatioBins().stream()
                        .collect(
                                Collectors.toMap(
                                        FundFlowRatioBinDTO::getCode,
                                        FundFlowRatioBinDTO::getAccounts));
        long sold = byCode.values().stream().mapToLong(Long::longValue).sum();
        List<FundFlowRatioBinDTO> bins = new ArrayList<>();
        for (AdjustRatioBin bin : AdjustRatioBin.values()) {
            long accounts = byCode.getOrDefault(bin.name(), 0L);
            bins.add(
                    FundFlowRatioBinDTO.builder()
                            .code(bin.name())
                            .label(bin.getLabel())
                            .accounts(accounts)
                            .share(ratio(BigDecimal.valueOf(accounts), BigDecimal.valueOf(sold)))
                            .build());
        }
        return bins;
    }

    /** 연령대별 + 전체 중앙값. 유입이 있는 계좌가 5개 미만이면 값을 가린다. */
    private List<FundFlowAgeMedianDTO> toAgeMedians(List<FundFlowAccountFlowRowDTO> rows) {
        List<FundFlowAgeMedianDTO> medians = new ArrayList<>();
        for (String ageBand : AGE_BANDS) {
            List<FundFlowAccountFlowRowDTO> band =
                    rows.stream().filter(r -> ageBand.equals(r.getAgeBand())).toList();
            if (!band.isEmpty()) {
                medians.add(toAgeMedian(ageBand, band));
            }
        }
        medians.add(toAgeMedian(null, rows));
        return medians;
    }

    private FundFlowAgeMedianDTO toAgeMedian(String ageBand, List<FundFlowAccountFlowRowDTO> rows) {
        List<FundFlowAccountFlowRowDTO> withInflow =
                rows.stream().filter(r -> r.getInflow().signum() > 0).toList();
        FundFlowAgeMedianDTO.FundFlowAgeMedianDTOBuilder median =
                FundFlowAgeMedianDTO.builder()
                        .ageBand(ageBand)
                        .accounts((long) rows.size())
                        .accountsWithInflow((long) withInflow.size());
        if (withInflow.size() >= MIN_CELL_ACCOUNTS) {
            median.inflowMedian(
                            FundFlowStatistics.median(
                                    withInflow.stream()
                                            .map(FundFlowAccountFlowRowDTO::getInflow)
                                            .toList()))
                    .reinvestMedian(
                            FundFlowStatistics.median(
                                    withInflow.stream()
                                            .map(r -> ratio(r.getBuyExec(), r.getInflow()))
                                            .toList()));
        }
        return median.build();
    }

    private FundFlowFilterDTO buildFilter(FundFlowPeriod period) {
        FundFlowFilterDTO filter = FundFlowFilterDTO.from(businessClockService.now());
        FundFlowPeriod applied = period == null ? FundFlowPeriod.ALL : period;
        filter.setPeriodFrom(applied.startOf(filter.getAsOfDate()));
        return filter;
    }

    /** 업무시계가 속한 달이 조회 범위 안이고, 그 달에 집계할 하루 이상이 지났을 때만 진행 중인 달을 낸다. */
    private boolean includesPartialMonth(FundFlowFilterDTO filter) {
        LocalDate monthStart = filter.getMonthStart();
        if (!filter.getClockDate().isAfter(monthStart)) {
            return false;
        }
        boolean afterFrom =
                filter.getFromMonth() == null || !monthStart.isBefore(filter.getFromMonth());
        boolean beforeTo = filter.getToMonth() == null || !monthStart.isAfter(filter.getToMonth());
        return afterFrom && beforeTo;
    }

    private String bucketLabel(FundFlowProductCostDTO product) {
        if (FUND_TYPE.equals(product.getType())) {
            return FUND_BUCKET;
        }
        return product.getSectorName() == null ? OTHER_SECTOR : product.getSectorName();
    }

    /** HHI = Σ점유율². 0~1, 1에 가까울수록 쏠림. */
    private BigDecimal hhi(Iterable<BigDecimal> costs, BigDecimal total) {
        if (total.signum() == 0) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal cost : costs) {
            BigDecimal share = cost.divide(total, INTERNAL_SCALE, RoundingMode.HALF_UP);
            sum = sum.add(share.multiply(share));
        }
        return sum.setScale(RATIO_SCALE, RoundingMode.HALF_UP);
    }

    private FundFlowSegmentRowDTO sum(
            String ageBand, String investorType, List<FundFlowSegmentRowDTO> rows) {
        return FundFlowSegmentRowDTO.builder()
                .ageBand(ageBand)
                .investorType(investorType)
                .accounts(rows.stream().mapToLong(FundFlowSegmentRowDTO::getAccounts).sum())
                .accountsWithInflow(
                        rows.stream().mapToLong(FundFlowSegmentRowDTO::getAccountsWithInflow).sum())
                .inflowTotal(sumAmount(rows, FundFlowSegmentRowDTO::getInflowTotal))
                .buyTotal(sumAmount(rows, FundFlowSegmentRowDTO::getBuyTotal))
                .sellTotal(sumAmount(rows, FundFlowSegmentRowDTO::getSellTotal))
                .build();
    }

    private BigDecimal sumAmount(
            List<FundFlowSegmentRowDTO> rows, Function<FundFlowSegmentRowDTO, BigDecimal> getter) {
        return rows.stream().map(getter).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private FundFlowSegmentCellDTO toCell(FundFlowSegmentRowDTO row) {
        FundFlowSegmentCellDTO.FundFlowSegmentCellDTOBuilder cell =
                FundFlowSegmentCellDTO.builder()
                        .ageBand(row.getAgeBand())
                        .investorType(row.getInvestorType())
                        .accounts(row.getAccounts())
                        .accountsWithInflow(row.getAccountsWithInflow());
        if (row.getAccounts() >= MIN_CELL_ACCOUNTS) {
            cell.inflowTotal(row.getInflowTotal())
                    .buyTotal(row.getBuyTotal())
                    .reinvestRatio(ratio(row.getBuyTotal(), row.getInflowTotal()))
                    .netInvestRatio(
                            ratio(
                                    row.getBuyTotal().subtract(row.getSellTotal()),
                                    row.getInflowTotal()));
        }
        return cell.build();
    }
}
