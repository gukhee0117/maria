package com.app.maria.domain.settlement.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.app.maria.domain.settlement.exception.ExchangeRateExternalApiException;
import com.app.maria.domain.settlement.exception.InvalidSettlementException;
import com.app.maria.global.client.exchange.ExchangeRateClient;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

@ExtendWith(MockitoExtension.class)
class ExchangeRateProviderImplTest {

    private static final LocalDate SEARCH_DATE = LocalDate.of(2026, 8, 4);

    @Mock private ExchangeRateClient exchangeRateClient;

    @InjectMocks private ExchangeRateProviderImpl exchangeRateProvider;

    @Test
    @DisplayName("통화와 기준일로 조회한 정상 환율을 반환한다")
    void getFinalRateReturnsRate() {
        BigDecimal expectedRate = new BigDecimal("1433.60");
        when(exchangeRateClient.getBaseRate("USD", SEARCH_DATE)).thenReturn(expectedRate);

        BigDecimal actualRate = exchangeRateProvider.getFinalRate("USD", SEARCH_DATE);

        assertThat(actualRate).isSameAs(expectedRate);
        verify(exchangeRateClient).getBaseRate("USD", SEARCH_DATE);
    }

    @Test
    @DisplayName("통화가 null이면 외부 환율을 조회하지 않고 요청 오류를 반환한다")
    void getFinalRateRejectsNullCurrency() {
        assertThatThrownBy(() -> exchangeRateProvider.getFinalRate(null, SEARCH_DATE))
                .isInstanceOf(InvalidSettlementException.class)
                .hasMessage("환율 조회 통화 입력");

        verifyNoInteractions(exchangeRateClient);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    @DisplayName("통화가 공백이면 외부 환율을 조회하지 않고 요청 오류를 반환한다")
    void getFinalRateRejectsBlankCurrency(String currency) {
        assertThatThrownBy(() -> exchangeRateProvider.getFinalRate(currency, SEARCH_DATE))
                .isInstanceOf(InvalidSettlementException.class)
                .hasMessage("환율 조회 통화 입력");

        verifyNoInteractions(exchangeRateClient);
    }

    @Test
    @DisplayName("기준일이 null이면 외부 환율을 조회하지 않고 요청 오류를 반환한다")
    void getFinalRateRejectsNullSearchDate() {
        assertThatThrownBy(() -> exchangeRateProvider.getFinalRate("USD", null))
                .isInstanceOf(InvalidSettlementException.class)
                .hasMessage("환율 기준일 입력");

        verifyNoInteractions(exchangeRateClient);
    }

    @Test
    @DisplayName("외부 환율 응답이 null이면 외부 API 오류로 처리한다")
    void getFinalRateRejectsNullRate() {
        when(exchangeRateClient.getBaseRate("USD", SEARCH_DATE)).thenReturn(null);

        assertThatThrownBy(() -> exchangeRateProvider.getFinalRate("USD", SEARCH_DATE))
                .isInstanceOf(ExchangeRateExternalApiException.class)
                .hasMessage("유효하지 않은 환율 응답");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-0.01"})
    @DisplayName("외부 환율 응답이 0 이하이면 외부 API 오류로 처리한다")
    void getFinalRateRejectsNonPositiveRate(String rate) {
        when(exchangeRateClient.getBaseRate("USD", SEARCH_DATE)).thenReturn(new BigDecimal(rate));

        assertThatThrownBy(() -> exchangeRateProvider.getFinalRate("USD", SEARCH_DATE))
                .isInstanceOf(ExchangeRateExternalApiException.class)
                .hasMessage("유효하지 않은 환율 응답");
    }

    @Test
    @DisplayName("외부 환율 Client의 조회 실패를 그대로 전파한다")
    void getFinalRatePropagatesClientException() {
        AppException clientException = new AppException(ErrorType.EXCHANGE_RATE_NOT_FOUND);
        when(exchangeRateClient.getBaseRate(eq("USD"), any(LocalDate.class)))
                .thenThrow(clientException);

        assertThatThrownBy(() -> exchangeRateProvider.getFinalRate("USD", SEARCH_DATE))
                .isSameAs(clientException);
    }

    @Test
    @DisplayName("외부 환율 Client의 연결 또는 응답 시간 초과를 외부 API 예외로 변환한다")
    void getFinalRateConvertsResourceAccessException() {
        ResourceAccessException timeoutException = new ResourceAccessException("Read timed out");
        when(exchangeRateClient.getBaseRate(eq("USD"), any(LocalDate.class)))
                .thenThrow(timeoutException);

        assertThatThrownBy(() -> exchangeRateProvider.getFinalRate("USD", SEARCH_DATE))
                .isInstanceOf(ExchangeRateExternalApiException.class)
                .hasMessage("환율 API 연결 또는 응답 시간 초과")
                .hasCause(timeoutException);

        verify(exchangeRateClient, times(3)).getBaseRate("USD", SEARCH_DATE);
    }

    @Test
    @DisplayName("timeout 이후 같은 날짜 환율 조회를 재시도해 성공하면 환율을 반환한다")
    void getFinalRateRetriesTransientTimeout() {
        ResourceAccessException timeoutException = new ResourceAccessException("Read timed out");
        when(exchangeRateClient.getBaseRate("USD", SEARCH_DATE))
                .thenThrow(timeoutException)
                .thenReturn(new BigDecimal("1433.60"));

        BigDecimal result = exchangeRateProvider.getFinalRate("USD", SEARCH_DATE);

        assertThat(result).isEqualByComparingTo("1433.60");
        verify(exchangeRateClient, times(2)).getBaseRate("USD", SEARCH_DATE);
    }

    @Test
    @DisplayName("HTTP 5xx 응답은 재시도 후 성공하면 환율을 반환한다")
    void getFinalRateRetriesServerError() {
        HttpServerErrorException serverException =
                HttpServerErrorException.create(
                        HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", null, null, null);
        when(exchangeRateClient.getBaseRate("USD", SEARCH_DATE))
                .thenThrow(serverException)
                .thenReturn(new BigDecimal("1433.60"));

        BigDecimal result = exchangeRateProvider.getFinalRate("USD", SEARCH_DATE);

        assertThat(result).isEqualByComparingTo("1433.60");
        verify(exchangeRateClient, times(2)).getBaseRate("USD", SEARCH_DATE);
    }

    @Test
    @DisplayName("HTTP 4xx 응답은 재시도하지 않는다")
    void getFinalRateDoesNotRetryClientError() {
        HttpClientErrorException clientException =
                HttpClientErrorException.create(
                        HttpStatus.BAD_REQUEST, "Bad Request", null, null, null);
        when(exchangeRateClient.getBaseRate("USD", SEARCH_DATE)).thenThrow(clientException);

        assertThatThrownBy(() -> exchangeRateProvider.getFinalRate("USD", SEARCH_DATE))
                .isInstanceOf(ExchangeRateExternalApiException.class)
                .hasMessage("환율 API 호출 실패");

        verify(exchangeRateClient).getBaseRate("USD", SEARCH_DATE);
    }

    @Test
    @DisplayName("재시도 대기 중 interrupt는 외부 API 오류로 처리한다")
    void getFinalRateRejectsInterruptedRetry() {
        ResourceAccessException timeoutException = new ResourceAccessException("Read timed out");
        when(exchangeRateClient.getBaseRate("USD", SEARCH_DATE)).thenThrow(timeoutException);

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> exchangeRateProvider.getFinalRate("USD", SEARCH_DATE))
                    .isInstanceOf(ExchangeRateExternalApiException.class)
                    .hasMessage("환율 API 재시도 대기 중단");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("외부 환율 Client의 일반 HTTP 호출 실패를 외부 API 예외로 변환한다")
    void getFinalRateConvertsRestClientException() {
        RestClientException clientException = new RestClientException("HTTP 호출 실패") {};
        when(exchangeRateClient.getBaseRate(eq("USD"), any(LocalDate.class)))
                .thenThrow(clientException);

        assertThatThrownBy(() -> exchangeRateProvider.getFinalRate("USD", SEARCH_DATE))
                .isInstanceOf(ExchangeRateExternalApiException.class)
                .hasMessage("환율 API 호출 실패")
                .hasCause(clientException);
    }

    @Test
    @DisplayName("Client가 환율 없음으로 반환하면 Provider에서 날짜 탐색을 중복하지 않는다")
    void getFinalRateDoesNotRepeatClientLookback() {
        AppException notFound = new AppException(ErrorType.EXCHANGE_RATE_NOT_FOUND);
        when(exchangeRateClient.getBaseRate("USD", SEARCH_DATE)).thenThrow(notFound);

        assertThatThrownBy(() -> exchangeRateProvider.getFinalRate("USD", SEARCH_DATE))
                .isSameAs(notFound);

        verify(exchangeRateClient).getBaseRate("USD", SEARCH_DATE);
    }
}
