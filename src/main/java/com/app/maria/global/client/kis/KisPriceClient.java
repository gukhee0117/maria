package com.app.maria.global.client.kis;

import com.app.maria.global.config.properties.PriceApiProperties;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@RequiredArgsConstructor
public class KisPriceClient {

    private final RestTemplate restTemplate;
    private final KisTokenService kisTokenService;
    private final PriceApiProperties priceApiProperties;

    private static final String TR_ID = "HHDFS00000300"; // 해외주식 현재가 상세/시세 조회 코드
    private static final String RATE_LIMIT_ERROR_CODE = "EGW00201"; // 초당 거래건수 초과
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 700; // KIS 데모키 초당 호출 제한 회피용

    public BigDecimal getPreviousClose(String exchangeCode, String ticker) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return fetchPreviousClose(exchangeCode, ticker);
            } catch (HttpServerErrorException e) {
                boolean isRateLimit = e.getResponseBodyAsString().contains(RATE_LIMIT_ERROR_CODE);
                if (!isRateLimit || attempt == MAX_ATTEMPTS) {
                    throw new  AppException(ErrorType.KIS_PRICE_NOT_FOUND,ticker);
                }
                sleep(RETRY_DELAY_MS);
            }
        }
        throw new AppException(ErrorType.KIS_PRICE_NOT_FOUND, ticker);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private BigDecimal fetchPreviousClose(String exchangeCode, String ticker) {
        // 거래소 코드 받아 전일 종가 반환
        String url =
                UriComponentsBuilder.fromHttpUrl(
                                priceApiProperties.getUrl()
                                        + "/uapi/overseas-price/v1/quotations/price")
                        .queryParam("AUTH", "")
                        .queryParam("EXCD", exchangeCode)
                        .queryParam("SYMB", ticker)
                        .toUriString();

        // Request header 설정
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("authorization", "Bearer " + kisTokenService.getAccessToken());
        headers.set("appkey", priceApiProperties.getAppKey());
        headers.set("appsecret", priceApiProperties.getAppSecret());
        headers.set("tr_id", TR_ID);
        headers.set("custtype", "P");

        HttpEntity<Void> request = new HttpEntity<>(headers);

        JsonNode response =
                restTemplate.exchange(url, HttpMethod.GET, request, JsonNode.class).getBody();

        // rt_cd는 응답 성공 여부 코드
        if (response == null || !"0".equals(response.path("rt_cd").asText())) {
            throw new AppException(ErrorType.KIS_PRICE_NOT_FOUND, ticker);
        }
        // output.base 전일 종가
        return new BigDecimal(response.path("output").path("base").asText());
    }
}
