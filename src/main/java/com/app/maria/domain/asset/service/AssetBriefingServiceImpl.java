package com.app.maria.domain.asset.service;

import com.app.maria.domain.asset.dto.AssetHoldingDTO;
import com.app.maria.domain.asset.dto.response.CustomerAssetBriefingResponseDTO;
import com.app.maria.domain.asset.price.AssetPriceProvider;
import com.app.maria.domain.asset.vo.AssetBriefingVO;
import com.app.maria.domain.asset.vo.AssetBriefingVO.MonthlyPurchase;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

@Service
@RequiredArgsConstructor
@Slf4j
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
        // 해외 평가·전일 대비는 해외 시세·환율, 전일 종가 연동 전이라 값이 없다. 총자산은 이 값들이 모두 있어야 계산된다.
        BigDecimal foreignValuation = null;
        BigDecimal totalAsset = totalAssetOf(snapshot.cash(), valuation.amount(), foreignValuation);
        unavailable.add("foreignHeldValuationAmount");
        if (totalAsset == null) unavailable.add("totalAssetAmount");
        unavailable.add("domesticDayChangeAmount");
        unavailable.add("domesticDayChangeRate");
        unavailable.add("dayChangeBaseDate");
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
        AssetBriefingVO result =
                AssetBriefingVO.builder()
                        .accountId(snapshot.accountId())
                        .cashAmount(snapshot.cash())
                        .investmentCostAmount(cost)
                        .cumulativeInflowAmount(inflow)
                        .investmentRatio(ratio)
                        .domesticValuationAmount(valuation.amount())
                        .valuationProfit(valuation.profit())
                        .valuationReturnRate(valuation.rate())
                        .foreignHeldValuationAmount(foreignValuation)
                        .totalAssetAmount(totalAsset)
                        .valuationPriceFrom(valuation.priceFrom())
                        .valuationPriceTo(valuation.priceTo())
                        .monthlyPurchases(monthly)
                        .unavailableFields(List.copyOf(unavailable))
                        .calculatedAt(snapshot.calculatedAt())
                        .build();
        return new CustomerAssetBriefingResponseDTO(result);
    }

    /** 총자산 = 예수금 + 국내 평가금액 + 입고 해외주식 평가액. 모르는 항목이 있으면 합을 만들지 않는다. */
    static BigDecimal totalAssetOf(BigDecimal cash, BigDecimal domestic, BigDecimal foreign) {
        if (cash == null || domestic == null || foreign == null) return null;
        return cash.add(domestic).add(foreign);
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
        Map<String, AssetPriceProvider.Quote> quotes;
        try {
            quotes = assetPriceProvider.getPrices(tickers);
        } catch (AppException e) {
            if (e.getErrorType() != ErrorType.ASSET_PRICE_UNAVAILABLE) throw e;
            log.warn("자산 브리핑 현재가 조회 불가: 평가값 없이 반환합니다.");
            return Valuation.unavailable();
        } catch (RestClientException e) {
            // 토큰·URL이 포함될 수 있는 원본 예외 메시지/응답은 로그에 남기지 않는다.
            log.warn("자산 브리핑 현재가 통신 실패: 평가값 없이 반환합니다.");
            return Valuation.unavailable();
        }
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
