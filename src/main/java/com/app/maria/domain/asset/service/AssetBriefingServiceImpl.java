package com.app.maria.domain.asset.service;

import com.app.maria.domain.asset.dto.AssetHoldingDTO;
import com.app.maria.domain.asset.dto.response.CustomerAssetBriefingResponseDTO;
import com.app.maria.domain.asset.dto.response.CustomerAssetBriefingResponseDTO.MonthlyPurchase;
import com.app.maria.domain.asset.price.AssetPriceProvider;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AssetBriefingServiceImpl implements AssetBriefingService {
    private final AssetBriefingSnapshotReader snapshotReader;
    private final AssetPriceProvider assetPriceProvider;

    @Override
    public CustomerAssetBriefingResponseDTO getBriefing(Long customerId) {
        var snapshot = snapshotReader.read(customerId);
        BigDecimal cost = snapshot.cost();
        BigDecimal inflow = snapshot.inflow();
        BigDecimal ratio =
                inflow.signum() > 0
                        ? cost.multiply(BigDecimal.valueOf(100))
                                .divide(inflow, 2, RoundingMode.HALF_UP)
                        : null;
        Valuation valuation = calculateValuation(snapshot.holdings(), cost);
        List<String> unavailable = new ArrayList<>();
        if (valuation.amount() == null) {
            unavailable.add("domesticValuationAmount");
            unavailable.add("valuationProfit");
        }
        if (valuation.rate() == null) unavailable.add("valuationReturnRate");
        unavailable.add("packageDeviation");
        if (ratio == null) unavailable.add("investmentRatio");
        List<MonthlyPurchase> monthly =
                snapshot.monthly().stream()
                        .map(
                                row ->
                                        new MonthlyPurchase(
                                                YearMonth.of(
                                                                row.getPurchaseYear(),
                                                                row.getPurchaseMonth())
                                                        .toString(),
                                                row.getBuyAmount()))
                        .toList();
        return CustomerAssetBriefingResponseDTO.builder()
                .accountId(snapshot.accountId())
                .cashAmount(snapshot.cash())
                .investmentCostAmount(cost)
                .cumulativeInflowAmount(inflow)
                .investmentRatio(ratio)
                .domesticValuationAmount(valuation.amount())
                .valuationProfit(valuation.profit())
                .valuationReturnRate(valuation.rate())
                .valuationPriceFrom(valuation.priceFrom())
                .valuationPriceTo(valuation.priceTo())
                .monthlyPurchases(monthly)
                .unavailableFields(List.copyOf(unavailable))
                .calculatedAt(snapshot.calculatedAt())
                .build();
    }

    private Valuation calculateValuation(List<AssetHoldingDTO> holdings, BigDecimal cost) {
        if (holdings.isEmpty()) {
            return cost.signum() == 0
                    ? new Valuation(BigDecimal.ZERO, BigDecimal.ZERO, null, null, null)
                    : Valuation.unavailable();
        }
        Set<String> tickers =
                holdings.stream()
                        .map(holding -> holding.getTicker())
                        .collect(Collectors.toUnmodifiableSet());
        var quotes = assetPriceProvider.getPrices(tickers);
        if (quotes == null) return Valuation.unavailable();
        BigDecimal amount = BigDecimal.ZERO;
        LocalDateTime priceFrom = null;
        LocalDateTime priceTo = null;
        for (var holding : holdings) {
            var quote = quotes.get(holding.getTicker());
            if (quote == null
                    || quote.priceKrw() == null
                    || quote.priceKrw().signum() <= 0
                    || quote.priceAt() == null) return Valuation.unavailable();
            amount = amount.add(holding.getQty().multiply(quote.priceKrw()));
            if (priceFrom == null || quote.priceAt().isBefore(priceFrom))
                priceFrom = quote.priceAt();
            if (priceTo == null || quote.priceAt().isAfter(priceTo)) priceTo = quote.priceAt();
        }
        BigDecimal profit = amount.subtract(cost);
        BigDecimal rate =
                cost.signum() > 0
                        ? profit.multiply(BigDecimal.valueOf(100))
                                .divide(cost, 2, RoundingMode.HALF_UP)
                        : null;
        return new Valuation(amount, profit, rate, priceFrom, priceTo);
    }

    private record Valuation(
            BigDecimal amount,
            BigDecimal profit,
            BigDecimal rate,
            LocalDateTime priceFrom,
            LocalDateTime priceTo) {
        static Valuation unavailable() {
            return new Valuation(null, null, null, null, null);
        }
    }
}
