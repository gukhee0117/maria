package com.app.maria.domain.asset.service;

import com.app.maria.domain.asset.dto.AssetHoldingDTO;
import com.app.maria.domain.asset.dto.response.CustomerAssetBriefingResponseDTO;
import com.app.maria.domain.asset.price.AssetPriceProvider;
import com.app.maria.domain.asset.vo.AssetBriefingVO;
import com.app.maria.domain.asset.vo.AssetBriefingVO.MonthlyPurchase;
import com.app.maria.domain.tax.dto.HeldLotDTO;
import com.app.maria.global.client.exchange.ExchangeRateClient;
import com.app.maria.global.client.kis.DomesticQuote;
import com.app.maria.global.client.kis.KisDomesticPriceClient;
import com.app.maria.global.client.kis.KisExchangeCode;
import com.app.maria.global.client.kis.KisPriceClient;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
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
    private final KisDomesticPriceClient kisDomesticPriceClient;
    private final KisPriceClient kisPriceClient;
    private final ExchangeRateClient exchangeRateClient;

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
        BigDecimal foreignValuation = foreignValuationOf(snapshot.foreignLots());
        BigDecimal totalAsset = totalAssetOf(snapshot.cash(), valuation.amount(), foreignValuation);
        if (foreignValuation == null) unavailable.add("foreignHeldValuationAmount");
        if (totalAsset == null) unavailable.add("totalAssetAmount");
        DayChange dayChange = dayChangeOf(snapshot.holdings());
        if (dayChange.amount() == null) unavailable.add("domesticDayChangeAmount");
        if (dayChange.rate() == null) unavailable.add("domesticDayChangeRate");
        // KIS 현재가 응답에 기준일 필드가 없어 비교한 거래일을 알 수 없다(오늘-1일로 추정하지 않는다).
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
                        .domesticDayChangeAmount(dayChange.amount())
                        .domesticDayChangeRate(dayChange.rate())
                        .dayChangePriceSource(dayChange.amount() == null ? null : "KIS_LIVE")
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

    /** 입고 해외주식 평가 = Σ 수량 × KIS 전일종가 × 환율. 보유 없으면 0, 하나라도 시세·환율을 못 받으면 null. */
    private BigDecimal foreignValuationOf(List<HeldLotDTO> lots) {
        if (lots.isEmpty()) return BigDecimal.ZERO;
        try {
            Map<String, BigDecimal> fxByCurrency = new HashMap<>();
            Map<Long, BigDecimal> priceByProduct = new HashMap<>();
            BigDecimal total = BigDecimal.ZERO;
            for (HeldLotDTO lot : lots) {
                BigDecimal fx =
                        fxByCurrency.computeIfAbsent(
                                lot.getCurrency(),
                                c -> exchangeRateClient.getBaseRateWithDate(c).getRate());
                BigDecimal price =
                        priceByProduct.computeIfAbsent(
                                lot.getForeignProductId(),
                                id ->
                                        kisPriceClient.getPreviousClose(
                                                KisExchangeCode.fromMarket(lot.getMarket()),
                                                lot.getTicker()));
                if (fx == null || fx.signum() <= 0 || price == null || price.signum() <= 0)
                    return null;
                total = total.add(lot.getCurrentQty().multiply(price).multiply(fx));
            }
            return total;
        } catch (AppException | RestClientException e) {
            // 토큰·URL이 포함될 수 있는 원본 예외 메시지는 로그에 남기지 않는다.
            log.warn("자산 브리핑 해외 시세·환율 조회 불가: 해외 평가 없이 반환합니다.");
            return null;
        }
    }

    /** 전일 대비 = Σ 수량 × (KIS 현재가 - 기준가). 보유 없으면 금액 0·비율 null, 하나라도 못 받으면 둘 다 null. */
    private DayChange dayChangeOf(List<AssetHoldingDTO> holdings) {
        if (holdings.isEmpty()) return new DayChange(BigDecimal.ZERO, null);
        try {
            BigDecimal change = BigDecimal.ZERO;
            BigDecimal base = BigDecimal.ZERO;
            for (AssetHoldingDTO holding : holdings) {
                DomesticQuote q = kisDomesticPriceClient.getQuote(holding.getTicker());
                change =
                        change.add(
                                holding.getQty().multiply(q.price().subtract(q.previousClose())));
                base = base.add(holding.getQty().multiply(q.previousClose()));
            }
            BigDecimal rate =
                    base.signum() > 0
                            ? change.multiply(BigDecimal.valueOf(100))
                                    .divide(base, 2, RoundingMode.HALF_UP)
                            : null;
            return new DayChange(change, rate);
        } catch (AppException | RestClientException e) {
            log.warn("자산 브리핑 전일 대비 조회 불가: 전일 대비 없이 반환합니다.");
            return new DayChange(null, null);
        }
    }

    private record DayChange(BigDecimal amount, BigDecimal rate) {}

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
