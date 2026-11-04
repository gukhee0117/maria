package com.app.maria.global.client.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.app.maria.domain.risk.config.RiskProperties;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PythonPackageRiskClientTest {
    private static final List<PythonPackageRiskClient.Item> ITEMS =
            List.of(
                    new PythonPackageRiskClient.Item("005930", new BigDecimal("0.6000")),
                    new PythonPackageRiskClient.Item("000660", new BigDecimal("0.4000")));

    private MockRestServiceServer server;
    private RiskProperties properties;
    private PythonPackageRiskClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://risk");
        server = MockRestServiceServer.bindTo(builder).build();
        properties = new RiskProperties();
        properties.setServiceKey("secret-key");
        client = new PythonPackageRiskClient(builder.build(), properties);
    }

    @Test
    @DisplayName("인증 헤더와 비율 비중으로 요청하고 변동성을 받는다")
    void success() {
        server.expect(requestTo("http://risk/package/volatility"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Risk-Service-Key", "secret-key"))
                .andExpect(jsonPath("$.items[0].ticker").value("005930"))
                .andExpect(jsonPath("$.items[0].weight").value(0.6))
                .andRespond(
                        withSuccess(
                                "{\"volatility\":0.2215,\"asOf\":\"2026-11-03T15:40:00+09:00\"}",
                                MediaType.APPLICATION_JSON));

        var result = client.fetchVolatility(ITEMS);

        assertThat(result.volatility()).isEqualByComparingTo("0.2215");
        assertThat(result.asOf()).isNotNull();
        server.verify();
    }

    @Test
    @DisplayName("키가 비어 있으면 요청을 보내지 않는다")
    void blankKeyBlocksRequest() {
        properties.setServiceKey(" ");

        assertThatThrownBy(() -> client.fetchVolatility(ITEMS))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.RISK_INVALID_RESPONSE));
        server.verify();
    }

    @Test
    @DisplayName("상태 코드별로 오류를 구분한다")
    void errorMapping() {
        assertFails(HttpStatus.UNAUTHORIZED, ErrorType.RISK_INVALID_RESPONSE);
        assertFails(HttpStatus.BAD_REQUEST, ErrorType.RISK_INVALID_RESPONSE);
        assertFails(HttpStatus.TOO_MANY_REQUESTS, ErrorType.RISK_UPSTREAM_UNAVAILABLE);
        assertFails(HttpStatus.SERVICE_UNAVAILABLE, ErrorType.RISK_UPSTREAM_UNAVAILABLE);
    }

    @Test
    @DisplayName("음수 변동성이나 빈 응답은 잘못된 응답이다")
    void invalidBody() {
        server.expect(requestTo("http://risk/package/volatility"))
                .andRespond(
                        withSuccess(
                                "{\"volatility\":-0.1,\"asOf\":\"2026-11-03T15:40:00+09:00\"}",
                                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchVolatility(ITEMS))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.RISK_INVALID_RESPONSE));
    }

    @Test
    @DisplayName("종목이 없거나 77개를 넘으면 요청하지 않는다")
    void itemLimit() {
        assertThatThrownBy(() -> client.fetchVolatility(List.of()))
                .isInstanceOf(AppException.class);
        List<PythonPackageRiskClient.Item> tooMany =
                java.util.stream.IntStream.range(0, 78)
                        .mapToObj(
                                i ->
                                        new PythonPackageRiskClient.Item(
                                                String.format("%06d", i), BigDecimal.ONE))
                        .toList();
        assertThatThrownBy(() -> client.fetchVolatility(tooMany)).isInstanceOf(AppException.class);
    }

    private void assertFails(HttpStatus status, ErrorType expected) {
        server.reset();
        server.expect(requestTo("http://risk/package/volatility")).andRespond(withStatus(status));
        assertThatThrownBy(() -> client.fetchVolatility(ITEMS))
                .isInstanceOfSatisfying(
                        AppException.class, e -> assertThat(e.getErrorType()).isEqualTo(expected));
    }
}
