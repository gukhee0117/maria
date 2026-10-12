package com.app.maria.domain.settlement.provider;

import com.app.maria.global.client.exchange.ExchangeRateClient;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
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

        return getRateWithRetry(currency, searchDate);
    }

    private BigDecimal getRateWithRetry(String currency, LocalDate lookupDate) {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            BigDecimal rate;
            try {
                rate = exchangeRateClient.getBaseRate(currency, lookupDate);
            } catch (AppException e) {
                if (e.getErrorType() == ErrorType.EXCHANGE_RATE_NOT_FOUND) {
                    throw e;
                }
                throw apiFailed(e.getMessage(), e);
            } catch (ResourceAccessException e) {
                if (attempt == maxRetries) {
                    throw apiFailed("환율 API 연결 또는 응답 시간 초과", e);
                }
                waitBeforeRetry(attempt, e);
                continue;
            } catch (RestClientResponseException e) {
                if (!isRetryableStatus(e.getStatusCode().value())) {
                    throw apiFailed("환율 API 호출 실패", e);
                }
                if (attempt == maxRetries) {
                    throw apiFailed("환율 API 호출 재시도 초과", e);
                }
                waitBeforeRetry(attempt, e);
                continue;
            } catch (RestClientException e) {
                throw apiFailed("환율 API 호출 실패", e);
            }
            // try 밖에서 검증해야 위 catch (AppException)에 다시 잡혀 이중 래핑되지 않는다.
            if (rate == null || rate.signum() <= 0) {
                throw apiFailed("유효하지 않은 환율 응답", null);
            }
            return rate;
        }

        throw apiFailed("환율 API 호출 실패", null);
    }

    private boolean isRetryableStatus(int statusCode) {
        return statusCode == 429 || statusCode >= 500 && statusCode <= 504;
    }

    private void waitBeforeRetry(int attempt, RuntimeException cause) {
        try {
            Thread.sleep(initialBackoffMillis * (1L << attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw apiFailed("환율 API 재시도 대기 중단", cause);
        }
    }

    private void validateCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            throw new AppException(ErrorType.SETTLEMENT_INVALID_REQUEST, "환율 조회 통화 입력");
        }
    }

    private void validateSearchDate(LocalDate searchDate) {
        if (searchDate == null) {
            throw new AppException(ErrorType.SETTLEMENT_INVALID_REQUEST, "환율 기준일 입력");
        }
    }

    private static AppException apiFailed(String message, Throwable cause) {
        return new AppException(ErrorType.EXCHANGE_RATE_API_FAILED, message, cause);
    }
}
