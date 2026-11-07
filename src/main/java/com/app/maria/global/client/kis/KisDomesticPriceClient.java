package com.app.maria.global.client.kis;

import com.app.maria.global.client.securities.SecuritiesMarketClient;
import com.app.maria.global.config.properties.PriceApiProperties;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** 국내주식 현재가(주식현재가 시세, FHKST01010100). 캐시 없이 요청마다 KIS에서 가져온다. */
@Component
public class KisDomesticPriceClient {

    private final RestTemplate restTemplate;
    private final KisTokenService kisTokenService;
    private final PriceApiProperties priceApiProperties;
    private final SecuritiesMarketClient securities;

    public KisDomesticPriceClient(
            RestTemplate restTemplate,
            KisTokenService kisTokenService,
            PriceApiProperties priceApiProperties) {
        this(restTemplate, kisTokenService, priceApiProperties, null);
    }

    @Autowired
    public KisDomesticPriceClient(
            RestTemplate restTemplate,
            KisTokenService kisTokenService,
            PriceApiProperties priceApiProperties,
            SecuritiesMarketClient securities) {
        this.restTemplate = restTemplate;
        this.kisTokenService = kisTokenService;
        this.priceApiProperties = priceApiProperties;
        this.securities = securities;
    }

    private static final String TR_ID = "FHKST01010100";
    private static final String MARKET_DIV_CODE = "J"; // 주식·ETF·ETN 통합
    private static final String RATE_LIMIT_ERROR_CODE = "EGW00201"; // 초당 거래건수 초과
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 700; // KIS 모의투자 서버 초당 호출 제한 회피용

    /** 시세를 받은 종목만 담는다. KIS 모드는 종목마다 순서대로 부르고 못 받은 종목은 건너뛴다. */
    public Map<String, DomesticQuote> getQuotes(Collection<String> tickers) {
        if (securities != null && securities.isActive()) {
            return securities.domesticQuotes(List.copyOf(tickers), securities.businessDate());
        }
        Map<String, DomesticQuote> quotes = new LinkedHashMap<>();
        for (String ticker : tickers) {
            try {
                quotes.put(ticker, getQuote(ticker));
            } catch (AppException e) {
                // 못 받은 종목은 결과에서 빠진다.
            }
        }
        return quotes;
    }

    public DomesticQuote getQuote(String ticker) {
        if (securities != null && securities.isActive()) {
            DomesticQuote quote = getQuotes(List.of(ticker)).get(ticker);
            if (quote == null) {
                throw new AppException(ErrorType.KIS_PRICE_NOT_FOUND, ticker);
            }
            return quote;
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return fetch(ticker);
            } catch (HttpServerErrorException e) {
                boolean rateLimited = e.getResponseBodyAsString().contains(RATE_LIMIT_ERROR_CODE);
                if (!rateLimited || attempt == MAX_ATTEMPTS) {
                    throw new AppException(ErrorType.KIS_PRICE_NOT_FOUND, ticker);
                }
                sleep();
            } catch (RestClientException e) {
                throw new AppException(ErrorType.KIS_PRICE_NOT_FOUND, ticker);
            }
        }
    }

    private void sleep() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private DomesticQuote fetch(String ticker) {
        String url =
                UriComponentsBuilder.fromHttpUrl(
                                priceApiProperties.getUrl()
                                        + "/uapi/domestic-stock/v1/quotations/inquire-price")
                        .queryParam("FID_COND_MRKT_DIV_CODE", MARKET_DIV_CODE)
                        .queryParam("FID_INPUT_ISCD", ticker)
                        .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("authorization", "Bearer " + kisTokenService.getAccessToken());
        headers.set("appkey", priceApiProperties.getAppKey());
        headers.set("appsecret", priceApiProperties.getAppSecret());
        headers.set("tr_id", TR_ID);
        headers.set("custtype", "P");

        JsonNode response =
                restTemplate
                        .exchange(
                                url, HttpMethod.GET, new HttpEntity<Void>(headers), JsonNode.class)
                        .getBody();
        if (response == null || !"0".equals(response.path("rt_cd").asText())) {
            throw new AppException(ErrorType.KIS_PRICE_NOT_FOUND, ticker);
        }

        JsonNode out = response.path("output");
        BigDecimal price = positive(out.path("stck_prpr").asText(), ticker); // 현재가
        return new DomesticQuote(
                ticker,
                price,
                positive(out.path("stck_sdpr").asText(), ticker), // 기준가(전일 종가)
                positive(out.path("stck_mxpr").asText(), ticker), // 상한가
                positive(out.path("stck_llam").asText(), ticker)); // 하한가
    }

    /** 빈 값·숫자가 아닌 값·0 이하는 시세를 받지 못한 것으로 본다(주문 가격에 쓰이므로 0원은 허용하지 않는다). */
    private static BigDecimal positive(String raw, String ticker) {
        try {
            BigDecimal value = new BigDecimal(raw.trim());
            if (value.signum() <= 0) {
                throw new AppException(ErrorType.KIS_PRICE_NOT_FOUND, ticker);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new AppException(ErrorType.KIS_PRICE_NOT_FOUND, ticker);
        }
    }
}
