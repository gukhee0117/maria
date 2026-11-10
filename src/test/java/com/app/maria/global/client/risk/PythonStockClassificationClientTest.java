package com.app.maria.global.client.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.app.maria.domain.risk.config.RiskProperties;
import com.app.maria.domain.risk.service.CurrentStockRiskService;
import com.app.maria.global.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PythonStockClassificationClientTest {
    private MockRestServiceServer server;
    private RiskProperties properties;
    private PythonStockClassificationClient client;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl("http://risk");
        server = MockRestServiceServer.bindTo(builder).build();
        properties = new RiskProperties();
        properties.setServiceKey("synthetic-key");
        client = new PythonStockClassificationClient(builder.build(), properties);
    }

    private String body(String ticker, int grade, String basis) {
        return "{\"ticker\":\""
                + ticker
                + "\",\"riskGrade\":"
                + grade
                + ",\"asOf\":\"2026-11-05T12:00:00+09:00\",\"basis\":\""
                + basis
                + "\"}";
    }

    @Test
    void gradeOnlyServiceUsesCurrentClassificationWithoutPriceMetrics() {
        server.expect(requestTo("http://risk/stocks/005930/classification"))
                .andExpect(header("X-Risk-Service-Key", "synthetic-key"))
                .andRespond(
                        withSuccess(
                                body("005930", 2, "STOCK_RULE_CURRENT_STATUS"),
                                MediaType.APPLICATION_JSON));
        var result =
                new CurrentStockRiskService(
                                client,
                                com.app.maria.domain.risk.RiskTestSupport.clock(
                                        "2026-11-05T12:00:00+09:00"),
                                77,
                                60)
                        .findCurrent("005930");
        assertThat(result.ticker()).isEqualTo("005930");
        assertThat(result.riskGrade()).isEqualTo(2);
        assertThat(result.asOf()).isNotNull();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3, 6})
    void rejectsNonStockGrades(int grade) {
        server.expect(requestTo("http://risk/stocks/005930/classification"))
                .andRespond(
                        withSuccess(
                                body("005930", grade, "STOCK_RULE_CURRENT_STATUS"),
                                MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.fetch("005930")).isInstanceOf(AppException.class);
        server.verify();
    }

    @Test
    void rejectsWrongTicker() {
        server.expect(requestTo("http://risk/stocks/005930/classification"))
                .andRespond(
                        withSuccess(
                                body("000660", 2, "STOCK_RULE_CURRENT_STATUS"),
                                MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.fetch("005930")).isInstanceOf(AppException.class);
        server.verify();
    }

    @Test
    void rejectsUnverifiedBasis() {
        server.expect(requestTo("http://risk/stocks/005930/classification"))
                .andRespond(withSuccess(body("005930", 2, "UNKNOWN"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.fetch("005930")).isInstanceOf(AppException.class);
        server.verify();
    }

    @Test
    void doesNotFallbackWhenProviderFails() {
        server.expect(requestTo("http://risk/stocks/005930/classification"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> client.fetch("005930")).isInstanceOf(AppException.class);
        server.verify();
    }

    @Test
    void missingKeyDoesNotSendRequest() {
        properties.setServiceKey("");
        assertThatThrownBy(() -> client.fetch("005930")).isInstanceOf(AppException.class);
        server.verify();
    }

    @Test
    void invalidTickerDoesNotSendRequest() {
        assertThatThrownBy(() -> client.fetch("../grades")).isInstanceOf(AppException.class);
        server.verify();
    }
}
