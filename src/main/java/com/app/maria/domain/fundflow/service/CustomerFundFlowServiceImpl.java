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
import com.app.maria.domain.fundflow.dto.FundFlowTransactionDTO;
import com.app.maria.domain.fundflow.dto.FundFlowTransactionRowDTO;
import com.app.maria.domain.fundflow.dto.FundFlowTransactionsDTO;
import com.app.maria.domain.fundflow.dto.FundFlowWithdrawalLotDTO;
import com.app.maria.domain.fundflow.dto.FundFlowWithdrawalLotRowDTO;
import com.app.maria.domain.fundflow.dto.FundFlowWithdrawalScheduleDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowCustomerAllocationResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowCustomerReliefResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowCustomerSummaryResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowMonthlyResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowTransactionsResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowWithdrawalScheduleResponseDTO;
import com.app.maria.domain.fundflow.mapper.FundFlowMapper;
import com.app.maria.domain.fundflow.type.LeakageCategory;
import com.app.maria.global.clock.service.BusinessClockService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private static final int AVG_PRICE_SCALE = 4;

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

    @Override
    public FundFlowTransactionsResponseDTO getTransactions(Long customerId) {
        FundFlowFilterDTO filter = buildFilter();
        if (!fundFlowMapper.existsCustomerOpenedAccount(customerId)) {
            return new FundFlowTransactionsResponseDTO(
                    FundFlowTransactionsDTO.builder()
                            .asOfDate(filter.getAsOfDate())
                            .hasAccount(false)
                            .items(List.of())
                            .build());
        }

        List<FundFlowTransactionRowDTO> rows = sortedRows(customerId, filter);

        List<FundFlowTransactionDTO> items = new ArrayList<>();
        BigDecimal cash = BigDecimal.ZERO;
        int start = 0;
        while (start < rows.size()) {
            FundFlowTransactionRowDTO head = rows.get(start);
            int end = start;
            BigDecimal amount = BigDecimal.ZERO;
            while (end < rows.size()
                    && rows.get(end).getEventDate().equals(head.getEventDate())
                    && rows.get(end).getType() == head.getType()) {
                amount = amount.add(rows.get(end).getCashAmount());
                end++;
            }
            cash = cash.add(amount);
            List<FundFlowTransactionRowDTO> details =
                    head.getType().hasProducts()
                            ? productDetails(rows.subList(start, end))
                            : List.of();
            items.add(
                    FundFlowTransactionDTO.builder()
                            .date(head.getEventDate())
                            .type(head.getType())
                            .amount(amount)
                            .cashAfter(cash)
                            .title(productTitle(details))
                            .details(details)
                            .build());
            start = end;
        }
        return new FundFlowTransactionsResponseDTO(
                FundFlowTransactionsDTO.builder()
                        .asOfDate(filter.getAsOfDate())
                        .hasAccount(true)
                        .items(items)
                        .build());
    }

    @Override
    public FundFlowWithdrawalScheduleResponseDTO getWithdrawalSchedule(Long customerId) {
        LocalDateTime now = businessClockService.now();
        if (!fundFlowMapper.existsCustomerOpenedAccount(customerId)) {
            return new FundFlowWithdrawalScheduleResponseDTO(
                    FundFlowWithdrawalScheduleDTO.builder()
                            .businessDate(now.toLocalDate())
                            .hasAccount(false)
                            .lots(List.of())
                            .build());
        }
        // 납입일(결제일) 단위로 합친다. 쿼리가 납입일 오름차순으로 주므로 순서를 그대로 쓴다.
        Map<LocalDate, FundFlowWithdrawalLotDTO> byDate = new LinkedHashMap<>();
        for (FundFlowWithdrawalLotRowDTO row :
                fundFlowMapper.selectCustomerWithdrawalLots(customerId)) {
            LocalDate paidDate = row.getFinalAt().toLocalDate();
            // 인출 처리와 같은 판정: 납입일시 + 1년이 지금(업무시계) 이전이거나 같으면 인출 가능
            boolean matured = !row.getFinalAt().plusYears(1).isAfter(now);
            FundFlowWithdrawalLotDTO lot =
                    byDate.computeIfAbsent(
                            paidDate,
                            d ->
                                    FundFlowWithdrawalLotDTO.builder()
                                            .paidDate(d)
                                            .maturityDate(d.plusYears(1))
                                            .paidAmount(BigDecimal.ZERO)
                                            .withdrawnAmount(BigDecimal.ZERO)
                                            .remainingAmount(BigDecimal.ZERO)
                                            .matured(true)
                                            .build());
            lot.setPaidAmount(lot.getPaidAmount().add(row.getPaidAmount()));
            lot.setWithdrawnAmount(lot.getWithdrawnAmount().add(row.getWithdrawnAmount()));
            lot.setRemainingAmount(lot.getRemainingAmount().add(row.getRemainingAmount()));
            lot.setMatured(lot.isMatured() && matured);
        }
        return new FundFlowWithdrawalScheduleResponseDTO(
                FundFlowWithdrawalScheduleDTO.builder()
                        .businessDate(now.toLocalDate())
                        .hasAccount(true)
                        .lots(new ArrayList<>(byDate.values()))
                        .build());
    }

    /** 같은 날 안에서는 구분 선언 순서(원화 확정 → 국내 매도 → 국내 매수 → 인출)로 늘어놓는다. */
    private List<FundFlowTransactionRowDTO> sortedRows(Long customerId, FundFlowFilterDTO filter) {
        List<FundFlowTransactionRowDTO> rows =
                new ArrayList<>(
                        fundFlowMapper.selectCustomerTransactionRows(
                                customerId, filter.getClockDate()));
        rows.sort(
                Comparator.comparing(FundFlowTransactionRowDTO::getEventDate)
                        .thenComparing(FundFlowTransactionRowDTO::getType));
        return rows;
    }

    /** 같은 날·같은 구분의 주문을 종목별로 합친다. 금액이 큰 종목부터, 같으면 종목명 순. */
    private List<FundFlowTransactionRowDTO> productDetails(List<FundFlowTransactionRowDTO> group) {
        Map<Long, FundFlowTransactionRowDTO> byProduct = new LinkedHashMap<>();
        for (FundFlowTransactionRowDTO row : group) {
            byProduct.merge(
                    row.getDomesticProductId(),
                    row.toBuilder().build(),
                    (a, b) -> {
                        a.setQty(a.getQty().add(b.getQty()));
                        a.setAmount(a.getAmount().add(b.getAmount()));
                        a.setFeeAmount(a.getFeeAmount().add(b.getFeeAmount()));
                        a.setTaxAmount(a.getTaxAmount().add(b.getTaxAmount()));
                        a.setCashAmount(a.getCashAmount().add(b.getCashAmount()));
                        return a;
                    });
        }
        return byProduct.values().stream()
                .sorted(
                        Comparator.comparing(FundFlowTransactionRowDTO::getAmount)
                                .reversed()
                                .thenComparing(FundFlowTransactionRowDTO::getProductName))
                .toList();
    }

    /** "OO 외 N" — 종목이 하나면 종목명만, 종목 내역이 없는 구분이면 null. */
    private String productTitle(List<FundFlowTransactionRowDTO> details) {
        if (details.isEmpty()) {
            return null;
        }
        String first = details.get(0).getProductName();
        return details.size() == 1 ? first : first + " 외 " + (details.size() - 1);
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
