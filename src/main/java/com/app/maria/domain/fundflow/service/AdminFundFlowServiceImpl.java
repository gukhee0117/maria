package com.app.maria.domain.fundflow.service;

import static com.app.maria.domain.fundflow.service.FundFlowRatios.RATIO_SCALE;
import static com.app.maria.domain.fundflow.service.FundFlowRatios.ratio;

import com.app.maria.domain.fundflow.dto.FundFlowAllocationDTO;
import com.app.maria.domain.fundflow.dto.FundFlowBucketDTO;
import com.app.maria.domain.fundflow.dto.FundFlowFilterDTO;
import com.app.maria.domain.fundflow.dto.FundFlowFunnelDTO;
import com.app.maria.domain.fundflow.dto.FundFlowLeakageCategoryDTO;
import com.app.maria.domain.fundflow.dto.FundFlowLeakageDTO;
import com.app.maria.domain.fundflow.dto.FundFlowMonthDTO;
import com.app.maria.domain.fundflow.dto.FundFlowMonthlyDTO;
import com.app.maria.domain.fundflow.dto.FundFlowOverviewDTO;
import com.app.maria.domain.fundflow.dto.FundFlowProductCostDTO;
import com.app.maria.domain.fundflow.dto.FundFlowReliefDTO;
import com.app.maria.domain.fundflow.dto.FundFlowSegmentCellDTO;
import com.app.maria.domain.fundflow.dto.FundFlowSegmentDTO;
import com.app.maria.domain.fundflow.dto.FundFlowSegmentRowDTO;
import com.app.maria.domain.fundflow.dto.request.FundFlowMonthlyRequestDTO;
import com.app.maria.domain.fundflow.dto.request.FundFlowReliefRequestDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowAllocationResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowFunnelResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowLeakageResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowMonthlyResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowOverviewResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowReliefResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowSegmentResponseDTO;
import com.app.maria.domain.fundflow.mapper.FundFlowMapper;
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

    private final FundFlowMapper fundFlowMapper;
    private final BusinessClockService businessClockService;

    @Override
    public FundFlowFunnelResponseDTO getFunnel() {
        FundFlowFilterDTO filter = buildFilter();
        return new FundFlowFunnelResponseDTO(
                FundFlowFunnelDTO.builder()
                        .asOfDate(filter.getAsOfDate())
                        .stages(fundFlowMapper.selectFunnel(filter))
                        .build());
    }

    @Override
    public FundFlowOverviewResponseDTO getOverview() {
        FundFlowFilterDTO filter = buildFilter();
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
        FundFlowFilterDTO filter = buildFilter();
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
                        .build());
    }

    @Override
    public FundFlowMonthlyResponseDTO getMonthly(FundFlowMonthlyRequestDTO request) {
        FundFlowFilterDTO filter = buildFilter();
        if (request.getFromMonth() != null) {
            filter.setFromMonth(request.getFromMonth().atDay(1));
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
    public FundFlowSegmentResponseDTO getSegments() {
        FundFlowFilterDTO filter = buildFilter();
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
                FundFlowSegmentDTO.builder().asOfDate(filter.getAsOfDate()).cells(cells).build());
    }

    @Override
    public FundFlowReliefResponseDTO getRelief(FundFlowReliefRequestDTO request) {
        ReliefScope scope = request.getScope() == null ? ReliefScope.ALL : request.getScope();
        FundFlowFilterDTO filter = buildFilter();
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
    public FundFlowAllocationResponseDTO getAllocation() {
        FundFlowFilterDTO filter = buildFilter();
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

    private FundFlowFilterDTO buildFilter() {
        return FundFlowFilterDTO.from(businessClockService.now());
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
