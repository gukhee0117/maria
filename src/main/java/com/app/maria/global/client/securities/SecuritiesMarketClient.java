package com.app.maria.global.client.securities;

import com.app.maria.global.client.exchange.RateWithDate;
import com.app.maria.global.client.kis.DomesticQuote;
import com.app.maria.global.client.kis.PriceWithDate;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.app.maria.global.response.ApiResponseDTO;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 증권사 시세·환율 API 클라이언트. 소스가 securities일 때만 다른 클라이언트가 위임한다. 실패는 URL·응답 본문 없이 AppException으로만 알린다. */
@Component
public class SecuritiesMarketClient {
    private static final int BATCH_SIZE = 50;
    private static final BigDecimal UPPER_LIMIT_RATE = new BigDecimal("1.3");
    private static final BigDecimal LOWER_LIMIT_RATE = new BigDecimal("0.7");

    private final RestClient client;
    private final BusinessClockService businessClockService;
    private final MarketSource source;

    public SecuritiesMarketClient(
            @Qualifier("assetPriceRestClient") RestClient client,
            BusinessClockService businessClockService,
            @Value("${custom.market.source:securities}") String source) {
        this.client = client;
        this.businessClockService = businessClockService;
        this.source = MarketSource.from(source);
    }

    public boolean isActive() {
        return source == MarketSource.SECURITIES;
    }

    public LocalDate businessDate() {
        return businessClockService.now().toLocalDate();
    }

    /** 요청한 종목 중 증권사에 가격이 있는 종목만 담는다. */
    public Map<String, DomesticQuote> domesticQuotes(Collection<String> tickers, LocalDate asOf) {
        List<String> requested = List.copyOf(new LinkedHashSet<>(tickers));
        Map<String, DomesticQuote> quotes = new HashMap<>();
        for (int from = 0; from < requested.size(); from += BATCH_SIZE) {
            List<String> batch =
                    requested.subList(from, Math.min(from + BATCH_SIZE, requested.size()));
            for (DomesticRow row :
                    fetch(
                            "/api/domestic-prices",
                            "tickers",
                            batch,
                            asOf,
                            new ParameterizedTypeReference<ApiResponseDTO<List<DomesticRow>>>() {},
                            ErrorType.KIS_PRICE_NOT_FOUND,
                            String.join(",", batch))) {
                if (row == null
                        || !batch.contains(row.ticker())
                        || row.priceKrw() == null
                        || row.priceKrw().signum() <= 0) {
                    continue;
                }
                // 이력이 하루뿐이면 전일 종가가 없으므로 현재가를 기준가로 쓴다.
                BigDecimal previous =
                        row.previousCloseKrw() != null && row.previousCloseKrw().signum() > 0
                                ? row.previousCloseKrw()
                                : row.priceKrw();
                quotes.put(
                        row.ticker(),
                        new DomesticQuote(
                                row.ticker(),
                                row.priceKrw(),
                                previous,
                                previous.multiply(UPPER_LIMIT_RATE).setScale(0, RoundingMode.DOWN),
                                previous.multiply(LOWER_LIMIT_RATE)
                                        .setScale(0, RoundingMode.DOWN)));
            }
        }
        return quotes;
    }

    /**
     * 종목별 최근 days개 영업일 종가(asOf 이하, 날짜 오름차순). 요청한 종목 중 증권사에 종가가 있는 종목만 담는다. 증권사가 asOf 이후 날짜를 주더라도
     * 여기서 걸러서 업무일 이후의 값이 화면에 나가지 않게 한다.
     */
    public Map<String, List<ClosePoint>> domesticHistory(
            Collection<String> tickers, LocalDate asOf, int days) {
        List<String> requested = List.copyOf(new LinkedHashSet<>(tickers));
        Map<String, List<ClosePoint>> history = new HashMap<>();
        for (int from = 0; from < requested.size(); from += BATCH_SIZE) {
            List<String> batch =
                    requested.subList(from, Math.min(from + BATCH_SIZE, requested.size()));
            for (HistoryRow row :
                    fetch(
                            "/api/domestic-prices/history",
                            "tickers",
                            batch,
                            asOf,
                            days,
                            new ParameterizedTypeReference<ApiResponseDTO<List<HistoryRow>>>() {},
                            ErrorType.KIS_PRICE_NOT_FOUND,
                            String.join(",", batch))) {
                if (row == null || !batch.contains(row.ticker()) || row.points() == null) {
                    continue;
                }
                List<ClosePoint> points =
                        row.points().stream()
                                .filter(
                                        p ->
                                                p != null
                                                        && p.baseDate() != null
                                                        && !p.baseDate().isAfter(asOf)
                                                        && p.closeKrw() != null
                                                        && p.closeKrw().signum() > 0)
                                .sorted(Comparator.comparing(ClosePoint::baseDate))
                                .toList();
                if (!points.isEmpty()) {
                    history.put(row.ticker(), points);
                }
            }
        }
        return history;
    }

    public PriceWithDate foreignPreviousClose(String ticker, LocalDate asOf) {
        for (ForeignRow row :
                fetch(
                        "/api/foreign-prices",
                        "tickers",
                        List.of(ticker),
                        asOf,
                        new ParameterizedTypeReference<ApiResponseDTO<List<ForeignRow>>>() {},
                        ErrorType.KIS_PRICE_NOT_FOUND,
                        ticker)) {
            if (row != null
                    && ticker.equals(row.ticker())
                    && row.previousClose() != null
                    && row.previousClose().signum() > 0) {
                return new PriceWithDate(row.previousClose(), row.previousCloseDate());
            }
        }
        throw new AppException(ErrorType.KIS_PRICE_NOT_FOUND, ticker);
    }

    /** 통화 1단위 기준 고시 환율. JPY도 증권사가 이미 1엔 기준으로 주므로 다시 나누지 않는다. */
    public RateWithDate baseRate(String currency, LocalDate asOf) {
        FxRow row = fxRow(currency, asOf);
        return new RateWithDate(row.baseRate(), row.rateDate());
    }

    public BigDecimal finalRate(String currency, LocalDate asOf) {
        return fxRow(currency, asOf).finalRate();
    }

    private FxRow fxRow(String currency, LocalDate asOf) {
        for (FxRow row :
                fetch(
                        "/api/fx-rates",
                        "currencies",
                        List.of(currency),
                        asOf,
                        new ParameterizedTypeReference<ApiResponseDTO<List<FxRow>>>() {},
                        ErrorType.EXCHANGE_RATE_NOT_FOUND,
                        currency)) {
            if (row != null
                    && currency.equals(row.currency())
                    && row.baseRate() != null
                    && row.baseRate().signum() > 0
                    && row.finalRate() != null
                    && row.finalRate().signum() > 0) {
                return row;
            }
        }
        throw new AppException(ErrorType.EXCHANGE_RATE_NOT_FOUND, currency);
    }

    private <T> List<T> fetch(
            String path,
            String param,
            List<String> keys,
            LocalDate asOf,
            ParameterizedTypeReference<ApiResponseDTO<List<T>>> type,
            ErrorType failure,
            String errorData) {
        return fetch(path, param, keys, asOf, null, type, failure, errorData);
    }

    private <T> List<T> fetch(
            String path,
            String param,
            List<String> keys,
            LocalDate asOf,
            Integer days,
            ParameterizedTypeReference<ApiResponseDTO<List<T>>> type,
            ErrorType failure,
            String errorData) {
        try {
            ApiResponseDTO<List<T>> response =
                    client.get()
                            .uri(
                                    builder -> {
                                        builder.path(path)
                                                .queryParam(param, String.join(",", keys))
                                                .queryParam("asOf", asOf);
                                        if (days != null) {
                                            builder.queryParam("days", days);
                                        }
                                        return builder.build();
                                    })
                            .accept(MediaType.APPLICATION_JSON)
                            .retrieve()
                            .body(type);
            if (response == null || response.getData() == null) {
                throw new AppException(failure, errorData);
            }
            return response.getData();
        } catch (RestClientException e) {
            // 호스트·URL·응답 본문을 예외에 싣지 않는다(원인도 달지 않는다).
            throw new AppException(failure, errorData);
        }
    }

    record DomesticRow(String ticker, BigDecimal priceKrw, BigDecimal previousCloseKrw) {}

    /** 증권사가 주는 일별 종가 한 점. */
    public record ClosePoint(LocalDate baseDate, BigDecimal closeKrw) {}

    record HistoryRow(String ticker, List<ClosePoint> points) {}

    record ForeignRow(String ticker, BigDecimal previousClose, LocalDate previousCloseDate) {}

    record FxRow(String currency, BigDecimal baseRate, BigDecimal finalRate, LocalDate rateDate) {}
}
