package com.app.maria.domain.asset.price;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.ErrorType;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SecuritiesAssetPriceProviderTest {
    private MockRestServiceServer server;
    private SecuritiesAssetPriceProvider provider;
    private final BusinessClockService clock = org.mockito.Mockito.mock(BusinessClockService.class);

    @BeforeEach
    void setup() {
        org.mockito.Mockito.when(clock.now()).thenReturn(LocalDateTime.of(2026, 4, 10, 9, 0));
        var builder = RestClient.builder().baseUrl("http://securities.test");
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new SecuritiesAssetPriceProvider(builder.build(), clock);
    }

    @Test
    void mapsRealContractAndKeepsUnavailableTickersAbsent() {
        server.expect(
                        requestTo(
                                containsString(
                                        "/api/domestic-prices?tickers=000660,005930&asOf=2026-04-10")))
                .andRespond(
                        withSuccess(
                                """
                    {"message":"ok","data":[
                      {"ticker":"005930","priceKrw":85604,"baseDate":"2026-11-17","priceAt":"2026-10-28T00:00:00","source":"SYNTHETIC"},
                      {"ticker":"000660","priceKrw":0,"priceAt":"2026-10-28T00:00:00"},
                      {"ticker":"999999","priceKrw":100,"priceAt":"2026-10-28T00:00:00"}]}
                    """,
                                MediaType.APPLICATION_JSON));
        var result = provider.getPrices(Set.of("005930", "000660"));
        assertThat(result).containsOnlyKeys("005930");
        assertThat(result.get("005930").priceKrw()).isEqualByComparingTo("85604");
        assertThat(result.get("005930").priceAt()).isEqualTo(LocalDateTime.of(2026, 10, 28, 0, 0));
        server.verify();
    }

    @Test
    void malformedResponseIsUnavailable() {
        server.expect(requestTo(containsString("tickers=005930")))
                .andRespond(
                        withSuccess(
                                "{\"message\":\"ok\",\"data\":null}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.getPrices(Set.of("005930")))
                .hasFieldOrPropertyWithValue("errorType", ErrorType.ASSET_PRICE_UNAVAILABLE);
        server.verify();
    }

    @Test
    void duplicateTickerIsContaminatedResponseAndFailsEntireLookup() {
        server.expect(requestTo(containsString("tickers=005930")))
                .andRespond(
                        withSuccess(
                                """
                    {"data":[
                      {"ticker":"005930","priceKrw":100,"priceAt":"2026-11-04T09:00:00"},
                      {"ticker":"005930","priceKrw":200,"priceAt":"2026-11-04T09:00:00"}]}
                    """,
                                MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.getPrices(Set.of("005930")))
                .hasFieldOrPropertyWithValue("errorType", ErrorType.ASSET_PRICE_UNAVAILABLE);
        server.verify();
    }

    @Test
    void noHoldingsDoesNotCallExternalApi() {
        assertThat(provider.getPrices(Set.of())).isEmpty();
        server.verify();
    }
}
