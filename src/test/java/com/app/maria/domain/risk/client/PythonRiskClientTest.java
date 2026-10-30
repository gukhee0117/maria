package com.app.maria.domain.risk.client;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.app.maria.domain.risk.config.RiskProperties;
import com.app.maria.global.client.risk.PythonRiskClient;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PythonRiskClientTest {
    static final String TEST_KEY = "synthetic-http-integration-key";

    PythonRiskClient authenticated(RestClient client) {
        var properties = new RiskProperties();
        properties.setServiceKey(TEST_KEY);
        return new PythonRiskClient(client, properties);
    }

    static final String ROW =
            """
        {"ticker":"005930","var975":0.3125,"riskGrade":2,"volatility":0.2410,
        "priceFrom":"2023-10-30","priceTo":"2026-10-28","asOf":"2026-10-28T08:40:20.123456Z","analysisVersion":1}
        """;

    @Test
    void readsExistingArrayContractAndVersionOne() {
        var b = RestClient.builder().baseUrl("http://risk.test");
        var server = MockRestServiceServer.bindTo(b).build();
        server.expect(requestTo("http://risk.test/grades"))
                .andExpect(header("X-Risk-Service-Key", TEST_KEY))
                .andRespond(withSuccess("[" + ROW + "]", MediaType.APPLICATION_JSON));
        var rows = authenticated(b.build()).fetch();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).analysisVersion()).isEqualTo(1);
        server.verify();
    }

    @Test
    void emptyArrayIsValidPartialPublicationAndVersionZeroIsRejected() {
        for (String body :
                java.util.List.of(
                        "[]",
                        "[" + ROW.replace("\"analysisVersion\":1", "\"analysisVersion\":0") + "]",
                        "[" + ROW + "," + ROW + "]")) {
            var b = RestClient.builder().baseUrl("http://risk.test");
            var server = MockRestServiceServer.bindTo(b).build();
            server.expect(requestTo("http://risk.test/grades"))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            var client = authenticated(b.build());
            if (body.equals("[]")) assertThat(client.fetch()).isEmpty();
            else assertThatThrownBy(client::fetch).isInstanceOf(AppException.class);
        }
    }

    @Test
    void distinguishesTransientFromPermanentHttpFailureWithoutEchoingBody() {
        for (HttpStatus status :
                java.util.List.of(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        HttpStatus.BAD_REQUEST,
                        HttpStatus.UNAUTHORIZED,
                        HttpStatus.FORBIDDEN,
                        HttpStatus.TOO_MANY_REQUESTS)) {
            var b = RestClient.builder().baseUrl("http://risk.test");
            var server = MockRestServiceServer.bindTo(b).build();
            server.expect(anything()).andRespond(withStatus(status).body("private-test-payload"));
            try {
                authenticated(b.build()).fetch();
                fail("must reject");
            } catch (AppException e) {
                assertThat(e.getErrorType())
                        .isEqualTo(
                                status.is5xxServerError() || status == HttpStatus.TOO_MANY_REQUESTS
                                        ? ErrorType.RISK_UPSTREAM_UNAVAILABLE
                                        : ErrorType.RISK_INVALID_RESPONSE);
                assertThat(e.getMessage()).doesNotContain("private-test-payload");
            }
        }
    }

    @Test
    void missingOrInvalidKeyFailsBeforeAnyHttpRequest() {
        for (String key : new String[] {null, "", " ", "invalid\r\nheader"}) {
            var builder = RestClient.builder().baseUrl("http://risk.test");
            var server = MockRestServiceServer.bindTo(builder).build();
            var properties = new RiskProperties();
            properties.setServiceKey(key);
            var client = new PythonRiskClient(builder.build(), properties);
            assertThatThrownBy(client::fetch)
                    .isInstanceOfSatisfying(
                            AppException.class,
                            e ->
                                    assertThat(e.getErrorType())
                                            .isEqualTo(ErrorType.RISK_INVALID_RESPONSE));
            server.verify();
        }
    }
}
