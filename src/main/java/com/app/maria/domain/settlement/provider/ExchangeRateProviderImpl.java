package com.app.maria.domain.settlement.provider;

import com.app.maria.domain.settlement.exception.ExchangeRateExternalApiException;
import com.app.maria.domain.settlement.exception.InvalidSettlementException;
import com.app.maria.global.client.exchange.ExchangeRateClient;
import com.app.maria.global.error.AppException;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class ExchangeRateProviderImpl implements ExchangeRateProvider {

    @Value("${custom.exchange.max-retries:2}")
    private int maxRetries = 2;

    @Value("${custom.exchange.initial-backoff:1000}")
    private long initialBackoffMillis = 1_000L;

    private final ExchangeRateClient exchangeRateClient;

    public ExchangeRateProviderImpl(
            @Qualifier("settlementExchangeRateClient") ExchangeRateClient exchangeRateClient) {
        this.exchangeRateClient = exchangeRateClient;
    }

    @Override
    public BigDecimal getFinalRate(String currency, LocalDate searchDate) {
        validateCurrency(currency);
        validateSearchDate(searchDate);

        try {
            return getRateWithRetry(currency, searchDate);
        } catch (AppException e) {
            throw e;
        } catch (ExchangeRateApiException e) {
            throw new ExchangeRateExternalApiException(e.getMessage(), e.getCause());
        }
    }

    private BigDecimal getRateWithRetry(String currency, LocalDate lookupDate) {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                BigDecimal rate = exchangeRateClient.getBaseRate(currency, lookupDate);
                if (rate == null || rate.signum() <= 0) {
                    throw new ExchangeRateApiException("유효하지 않은 환율 응답", null);
                }
                return rate;
            } catch (AppException e) {
                throw e;
            } catch (ResourceAccessException e) {
                if (attempt == maxRetries) {
                    throw new ExchangeRateApiException("환율 API 연결 또는 응답 시간 초과", e);
                }
                waitBeforeRetry(attempt, e);
            } catch (RestClientResponseException e) {
                if (!isRetryableStatus(e.getStatusCode().value())) {
                    throw new ExchangeRateApiException("환율 API 호출 실패", e);
                }
                if (attempt == maxRetries) {
                    throw new ExchangeRateApiException("환율 API 호출 재시도 초과", e);
                }
                waitBeforeRetry(attempt, e);
            } catch (RestClientException e) {
                throw new ExchangeRateApiException("환율 API 호출 실패", e);
            }
        }

        throw new ExchangeRateApiException("환율 API 호출 실패", null);
    }

    private boolean isRetryableStatus(int statusCode) {
        return statusCode == 429 || statusCode >= 500 && statusCode <= 504;
    }

    private void waitBeforeRetry(int attempt, RuntimeException cause) {
        try {
            Thread.sleep(initialBackoffMillis * (1L << attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExchangeRateApiException("환율 API 재시도 대기 중단", cause);
        }
    }

    private void validateCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            throw new InvalidSettlementException("환율 조회 통화 입력");
        }
    }

    private void validateSearchDate(LocalDate searchDate) {
        if (searchDate == null) {
            throw new InvalidSettlementException("환율 기준일 입력");
        }
    }

    private static class ExchangeRateApiException extends RuntimeException {
        private ExchangeRateApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
