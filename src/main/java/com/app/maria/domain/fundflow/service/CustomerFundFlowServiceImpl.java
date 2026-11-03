package com.app.maria.domain.fundflow.service;

import static com.app.maria.domain.fundflow.service.FundFlowRatios.ratio;

import com.app.maria.domain.fundflow.dto.FundFlowBucketDTO;
import com.app.maria.domain.fundflow.dto.FundFlowCustomerAllocationDTO;
import com.app.maria.domain.fundflow.dto.FundFlowCustomerReliefDTO;
import com.app.maria.domain.fundflow.dto.FundFlowCustomerSummaryDTO;
import com.app.maria.domain.fundflow.dto.FundFlowFilterDTO;
import com.app.maria.domain.fundflow.dto.FundFlowMonthDTO;
import com.app.maria.domain.fundflow.dto.FundFlowMonthlyDTO;
import com.app.maria.domain.fundflow.dto.FundFlowProductCostDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowCustomerAllocationResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowCustomerReliefResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowCustomerSummaryResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowMonthlyResponseDTO;
import com.app.maria.domain.fundflow.mapper.FundFlowMapper;
import com.app.maria.domain.fundflow.type.LeakageCategory;
import com.app.maria.global.clock.service.BusinessClockService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomerFundFlowServiceImpl implements CustomerFundFlowService {

    private static final String NO_SNAPSHOT_LABEL = "아직 계산된 예상 혜택이 없어요";
    private static final String FUND_TYPE = "FUND";
    private static final String OTHER_SECTOR = "기타";
    private static final int SECTOR_TOP = 3;

    private final FundFlowMapper fundFlowMapper;
    private final BusinessClockService businessClockService;

    @Override
    public FundFlowCustomerSummaryResponseDTO getSummary(Long customerId) {
        FundFlowFilterDTO filter = buildFilter();
        FundFlowCustomerSummaryDTO summary =
                fundFlowMapper.selectCustomerSummary(customerId, filter.getClockDate());
        if (summary == null) {
            summary = FundFlowCustomerSummaryDTO.builder().hasAccount(false).build();
        } else {
            summary.setReinvestRatio(
                    ratio(summary.getDomesticBuyAmount(), summary.getKrwInflowAmount()));
        }
        summary.setAsOfDate(filter.getAsOfDate());
        return new FundFlowCustomerSummaryResponseDTO(summary);
    }

    @Override
    public FundFlowMonthlyResponseDTO getMonthly(Long customerId) {
        FundFlowFilterDTO filter = buildFilter();
        List<FundFlowMonthDTO> months =
                new ArrayList<>(
                        fundFlowMapper.selectCustomerMonthly(customerId, filter.getMonthStart()));
        // 월초(업무시계 == 1일)에는 집계할 하루가 아직 없어 진행 중인 달을 내지 않는다.
        if (filter.getClockDate().isAfter(filter.getMonthStart())) {
            FundFlowMonthDTO partial =
                    fundFlowMapper.selectCustomerMonthlyPartial(
                            customerId, filter.getMonthStart(), filter.getClockDate());
            if (partial != null) {
                partial.setFlowMonth(filter.getMonthStart());
                partial.setPartial(true);
                months.add(partial);
            }
        }
        return new FundFlowMonthlyResponseDTO(
                FundFlowMonthlyDTO.builder().asOfDate(filter.getAsOfDate()).months(months).build());
    }

    @Override
    public FundFlowCustomerReliefResponseDTO getRelief(Long customerId) {
        FundFlowFilterDTO filter = buildFilter();
        FundFlowCustomerReliefDTO relief =
                fundFlowMapper.selectCustomerRelief(customerId, filter.getAsOfDate());
        if (relief == null) {
            relief = FundFlowCustomerReliefDTO.builder().hasAccount(false).build();
        } else if (relief.getWeightedSell() == null) {
            relief.setStatusLabel(NO_SNAPSHOT_LABEL);
        } else {
            LeakageCategory category =
                    LeakageCategory.classify(
                            relief.getWeightedSell(), relief.getBenefit(), relief.getAdjustRatio());
            relief.setStatusCode(category.name());
            relief.setStatusLabel(category.getCustomerMessage());
            relief.setReliefAmount(relief.getGeneralTax().subtract(relief.getFinalTax()));
        }
        relief.setAsOfDate(filter.getAsOfDate());
        return new FundFlowCustomerReliefResponseDTO(relief);
    }

    @Override
    public FundFlowCustomerAllocationResponseDTO getAllocation(Long customerId) {
        FundFlowFilterDTO filter = buildFilter();
        FundFlowCustomerSummaryDTO summary =
                fundFlowMapper.selectCustomerSummary(customerId, filter.getClockDate());
        if (summary == null) {
            return new FundFlowCustomerAllocationResponseDTO(
                    FundFlowCustomerAllocationDTO.builder()
                            .asOfDate(filter.getAsOfDate())
                            .hasAccount(false)
                            .build());
        }

        List<FundFlowProductCostDTO> products =
                fundFlowMapper.selectCustomerProductCosts(customerId);
        BigDecimal stockCost = BigDecimal.ZERO;
        BigDecimal fundCost = BigDecimal.ZERO;
        Map<String, BigDecimal> costBySector = new LinkedHashMap<>();
        for (FundFlowProductCostDTO product : products) {
            if (FUND_TYPE.equals(product.getType())) {
                fundCost = fundCost.add(product.getPurchaseCost());
            } else {
                stockCost = stockCost.add(product.getPurchaseCost());
                String sector =
                        product.getSectorName() == null ? OTHER_SECTOR : product.getSectorName();
                costBySector.merge(sector, product.getPurchaseCost(), BigDecimal::add);
            }
        }
        BigDecimal holdings = stockCost.add(fundCost);
        BigDecimal cash =
                summary.getCashBalance() == null ? BigDecimal.ZERO : summary.getCashBalance();
        BigDecimal total = holdings.add(cash);

        List<FundFlowBucketDTO> sectors =
                costBySector.entrySet().stream()
                        .map(e -> bucket(e.getKey(), e.getValue(), holdings))
                        .sorted(Comparator.comparing(FundFlowBucketDTO::getPurchaseCost).reversed())
                        .limit(SECTOR_TOP)
                        .toList();

        return new FundFlowCustomerAllocationResponseDTO(
                FundFlowCustomerAllocationDTO.builder()
                        .asOfDate(filter.getAsOfDate())
                        .hasAccount(true)
                        .holdingsCost(holdings)
                        .cashBalance(cash)
                        .totalCost(total)
                        .buckets(
                                List.of(
                                        bucket("국내 주식", stockCost, total),
                                        bucket("ETF·펀드", fundCost, total),
                                        bucket("예수금", cash, total)))
                        .sectors(sectors)
                        .build());
    }

    private FundFlowBucketDTO bucket(String label, BigDecimal value, BigDecimal total) {
        return FundFlowBucketDTO.builder()
                .label(label)
                .purchaseCost(value)
                .share(ratio(value, total))
                .build();
    }

    private FundFlowFilterDTO buildFilter() {
        return FundFlowFilterDTO.from(businessClockService.now());
    }
}
