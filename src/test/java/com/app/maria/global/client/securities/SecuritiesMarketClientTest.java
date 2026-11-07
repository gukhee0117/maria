package com.app.maria.global.client.securities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.app.maria.global.client.kis.DomesticQuote;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SecuritiesMarketClientTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 4, 10);
    private MockRestServiceServer server;
    private SecuritiesMarketClient client;

    @BeforeEach
    void setup() {
        var builder = RestClient.builder().baseUrl("http://securities.internal.test:10001");
        server = MockRestServiceServer.bindTo(builder).build();
        client =
                new SecuritiesMarketClient(
                        builder.build(), mock(BusinessClockService.class), "securities");
    }

    @Test
    void 국내_12종목은_증권사를_한번만_부르고_상하한가는_전일종가의_130_70퍼센트_원단위_버림() {
        List<String> tickers =
                IntStream.range(0, 12).mapToObj(i -> String.format("%06d", i)).toList();
        StringBuilder rows = new StringBuilder();
        for (String t : tickers) {
            rows.append(
                    "{\"ticker\":\"%s\",\"priceKrw\":10500,\"previousCloseKrw\":10001,\"baseDate\":\"2026-04-09\"},"
                            .formatted(t));
        }
        rows.setLength(rows.length() - 1);
        server.expect(requestTo(containsString("/api/domestic-prices?tickers=000000,000001")))
                .andExpect(requestTo(containsString("asOf=2026-04-10")))
                .andRespond(
                        withSuccess(
                                "{\"message\":\"ok\",\"data\":[" + rows + "]}",
                                MediaType.APPLICATION_JSON));
        Map<String, DomesticQuote> quotes = client.domesticQuotes(tickers, AS_OF);
        assertThat(quotes).hasSize(12);
        DomesticQuote q = quotes.get("000003");
        assertThat(q.price()).isEqualByComparingTo("10500");
        assertThat(q.previousClose()).isEqualByComparingTo("10001");
        assertThat(q.upperLimit()).isEqualByComparingTo("13001"); // 13001.3 → 13001
        assertThat(q.lowerLimit()).isEqualByComparingTo("7000"); // 7000.7 → 7000
        server.verify();
    }

    @Test
    void 국내_전일종가가_없으면_현재가를_전일종가로_쓰고_없는_종목은_빠진다() {
        server.expect(requestTo(containsString("/api/domestic-prices")))
                .andRespond(
                        withSuccess(
                                "{\"message\":\"ok\",\"data\":[{\"ticker\":\"005930\",\"priceKrw\":1000}]}",
                                MediaType.APPLICATION_JSON));
        var quotes = client.domesticQuotes(List.of("005930", "000660"), AS_OF);
        assertThat(quotes).containsOnlyKeys("005930");
        assertThat(quotes.get("005930").previousClose()).isEqualByComparingTo("1000");
    }

    @Test
    void 해외_전일종가와_날짜() {
        server.expect(requestTo(containsString("/api/foreign-prices?tickers=QQQ&asOf=2026-04-10")))
                .andRespond(
                        withSuccess(
                                "{\"message\":\"ok\",\"data\":[{\"ticker\":\"QQQ\",\"previousClose\":570.3,\"previousCloseDate\":\"2026-04-09\"}]}",
                                MediaType.APPLICATION_JSON));
        var p = client.foreignPreviousClose("QQQ", AS_OF);
        assertThat(p.getPrice()).isEqualByComparingTo("570.3");
        assertThat(p.getBaseDate()).isEqualTo(LocalDate.of(2026, 4, 9));
    }

    @Test
    void 환율은_JPY도_다시_나누지_않고_고시와_확정을_구분한다() {
        String body =
                "{\"message\":\"ok\",\"data\":[{\"currency\":\"JPY\",\"baseRate\":9.3474,\"finalRate\":9.4,\"rateDate\":\"2026-04-10\"}]}";
        server.expect(requestTo(containsString("/api/fx-rates?currencies=JPY&asOf=2026-04-10")))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/api/fx-rates")))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        var base = client.baseRate("JPY", AS_OF);
        assertThat(base.getRate()).isEqualByComparingTo("9.3474");
        assertThat(base.getBaseDate()).isEqualTo(AS_OF);
        assertThat(client.finalRate("JPY", AS_OF)).isEqualByComparingTo("9.4");
    }

    @Test
    void 값이_없으면_AppException() {
        server.expect(requestTo(containsString("/api/foreign-prices")))
                .andRespond(
                        withSuccess(
                                "{\"message\":\"ok\",\"data\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/api/fx-rates")))
                .andRespond(
                        withSuccess(
                                "{\"message\":\"ok\",\"data\":[]}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.foreignPreviousClose("QQQ", AS_OF))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.KIS_PRICE_NOT_FOUND));
        assertThatThrownBy(() -> client.baseRate("USD", AS_OF))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.EXCHANGE_RATE_NOT_FOUND));
    }

    @Test
    void 서버오류_메시지에_호스트와_URL이_없다() {
        server.expect(requestTo(containsString("/api/")))
                .andRespond(withServerError().body("internal secret body"));
        server.expect(requestTo(containsString("/api/")))
                .andRespond(withServerError().body("internal secret body"));
        server.expect(requestTo(containsString("/api/")))
                .andRespond(withServerError().body("internal secret body"));
        for (Runnable call :
                List.<Runnable>of(
                        () -> client.domesticQuotes(List.of("005930"), AS_OF),
                        () -> client.foreignPreviousClose("QQQ", AS_OF),
                        () -> client.finalRate("USD", AS_OF))) {
            assertThatThrownBy(call::run)
                    .isInstanceOf(AppException.class)
                    .satisfies(
                            e -> {
                                assertThat(String.valueOf(e.getMessage()))
                                        .doesNotContain("securities.internal.test")
                                        .doesNotContain("http")
                                        .doesNotContain("secret body");
                                assertThat(e.getCause()).isNull();
                            });
        }
    }

    @Test
    void 소스가_kis면_비활성() {
        var off =
                new SecuritiesMarketClient(
                        RestClient.builder().build(), mock(BusinessClockService.class), "kis");
        assertThat(off.isActive()).isFalse();
        assertThat(client.isActive()).isTrue();
    }
}
