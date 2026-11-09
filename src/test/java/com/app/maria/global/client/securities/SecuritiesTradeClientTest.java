package com.app.maria.global.client.securities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.app.maria.domain.domestic.type.DomesticOrderSide;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SecuritiesTradeClientTest {
    private static final String URL =
            "http://securities.internal.test:10001/api/internal/domestic-trades";
    private MockRestServiceServer server;
    private SecuritiesTradeClient client;

    @BeforeEach
    void setup() {
        var builder = RestClient.builder().baseUrl("http://securities.internal.test:10001");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SecuritiesTradeClient(builder.build(), "s3cret", 3, 4000, new long[] {0, 0});
    }

    private void record() {
        client.record(
                "a".repeat(64),
                DomesticOrderSide.SELL,
                "005930",
                new BigDecimal("10"),
                new BigDecimal("87500"),
                LocalDateTime.of(2026, 11, 18, 10, 0),
                77L);
    }

    private static String ok() {
        return "{\"message\":\"거래 기록 성공\",\"data\":null}";
    }

    @Test
    void 비밀_헤더와_본문을_보낸다() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Secret", "s3cret"))
                .andExpect(jsonPath("$.ciHash").value("a".repeat(64)))
                .andExpect(jsonPath("$.side").value("SELL"))
                .andExpect(jsonPath("$.ticker").value("005930"))
                .andExpect(jsonPath("$.qty").value(10))
                .andExpect(jsonPath("$.price").value(87500))
                .andExpect(jsonPath("$.executedAt").value("2026-11-18T10:00:00"))
                .andExpect(jsonPath("$.mariaOrderId").value(77))
                .andRespond(withSuccess(ok(), MediaType.APPLICATION_JSON));
        record();
        server.verify();
    }

    @Test
    void 일시_오류는_다시_보내고_성공하면_끝() {
        server.expect(times(2), requestTo(URL)).andRespond(withServerError());
        server.expect(requestTo(URL)).andRespond(withSuccess(ok(), MediaType.APPLICATION_JSON));
        record();
        server.verify();
    }

    @Test
    void 세번_모두_실패하면_AppException이고_호스트와_본문은_메시지에_없다() {
        server.expect(times(3), requestTo(URL)).andRespond(withServerError().body("secret body"));
        assertThatThrownBy(this::record)
                .isInstanceOfSatisfying(
                        AppException.class,
                        e -> {
                            assertThat(e.getErrorType())
                                    .isEqualTo(ErrorType.STOCK_ORDER_SECURITIES_RECORD_FAILED);
                            assertThat(e.getMessage())
                                    .doesNotContain("securities.internal.test")
                                    .doesNotContain("secret body");
                            assertThat(e.getCause()).isNull();
                        });
        server.verify();
    }

    @Test
    void 잘못된_요청_4xx는_다시_보내지_않고_바로_실패() {
        server.expect(requestTo(URL)).andRespond(withBadRequest());
        assertThatThrownBy(this::record).isInstanceOf(AppException.class);
        server.verify();
    }

    @Test
    void 응답에_본문이_없어도_2xx면_성공() {
        server.expect(requestTo(URL)).andRespond(withSuccess());
        record();
        server.verify();
    }
}
