package com.app.maria.global.client.securities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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

/** 증권사 일별 종가 이력(/api/domestic-prices/history) 호출. */
class SecuritiesMarketClientHistoryTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 11, 18);
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

    private static String ok(String data) {
        return "{\"message\":\"ok\",\"data\":" + data + "}";
    }

    @Test
    void 종목과_기준일과_일수를_담아_한번에_부르고_날짜_오름차순으로_돌려준다() {
        server.expect(
                        requestTo(
                                containsString(
                                        "/api/domestic-prices/history?tickers=005930,000660&asOf=2026-11-18&days=30")))
                .andRespond(
                        withSuccess(
                                ok(
                                        "[{\"ticker\":\"005930\",\"points\":["
                                                + "{\"baseDate\":\"2026-11-17\",\"closeKrw\":71000.0000},"
                                                + "{\"baseDate\":\"2026-11-16\",\"closeKrw\":70000.0000}]},"
                                                + "{\"ticker\":\"000660\",\"points\":["
                                                + "{\"baseDate\":\"2026-11-17\",\"closeKrw\":150000}]}]"),
                                MediaType.APPLICATION_JSON));

        Map<String, List<SecuritiesMarketClient.ClosePoint>> history =
                client.domesticHistory(List.of("005930", "000660"), AS_OF, 30);

        assertThat(history).containsOnlyKeys("005930", "000660");
        var samsung = history.get("005930");
        assertThat(samsung).hasSize(2);
        // 증권사가 내림차순으로 줘도 날짜 오름차순으로 돌려준다
        assertThat(samsung.get(0).baseDate()).isEqualTo(LocalDate.of(2026, 11, 16));
        assertThat(samsung.get(1).closeKrw()).isEqualByComparingTo("71000");
        server.verify();
    }

    @Test
    void 업무일_이후_날짜와_0_이하_종가는_걸러내고_없는_종목은_빠진다() {
        server.expect(requestTo(containsString("/api/domestic-prices/history")))
                .andRespond(
                        withSuccess(
                                ok(
                                        "[{\"ticker\":\"005930\",\"points\":["
                                                + "{\"baseDate\":\"2026-11-17\",\"closeKrw\":71000},"
                                                + "{\"baseDate\":\"2026-11-18\",\"closeKrw\":72000},"
                                                + "{\"baseDate\":\"2026-11-19\",\"closeKrw\":73000},"
                                                + "{\"baseDate\":\"2026-11-13\",\"closeKrw\":0},"
                                                + "{\"baseDate\":\"2026-11-12\",\"closeKrw\":null}]},"
                                                + "{\"ticker\":\"035420\",\"points\":["
                                                + "{\"baseDate\":\"2026-11-19\",\"closeKrw\":9000}]},"
                                                + "{\"ticker\":\"777777\",\"points\":["
                                                + "{\"baseDate\":\"2026-11-17\",\"closeKrw\":1}]}]"),
                                MediaType.APPLICATION_JSON));

        var history = client.domesticHistory(List.of("005930", "035420", "000660"), AS_OF, 30);

        // 기준일(11-18) 당일은 포함, 11-19는 제외. 0원·NULL 종가는 제외
        assertThat(history.get("005930"))
                .extracting(SecuritiesMarketClient.ClosePoint::baseDate)
                .containsExactly(LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 18));
        // 전부 업무일 이후뿐인 종목, 요청하지 않은 종목, 응답에 없는 종목은 담기지 않는다
        assertThat(history).containsOnlyKeys("005930");
    }

    @Test
    void 종목이_50개를_넘으면_50개씩_나눠_부른다() {
        List<String> tickers =
                IntStream.range(0, 51).mapToObj(i -> String.format("%06d", i)).toList();
        server.expect(times(2), requestTo(containsString("/api/domestic-prices/history")))
                .andRespond(withSuccess(ok("[]"), MediaType.APPLICATION_JSON));

        assertThat(client.domesticHistory(tickers, AS_OF, 30)).isEmpty();

        server.verify();
    }

    @Test
    void 증권사_오류는_AppException이다() {
        server.expect(requestTo(containsString("/api/domestic-prices/history")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.domesticHistory(List.of("005930"), AS_OF, 30))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorType())
                .isEqualTo(ErrorType.KIS_PRICE_NOT_FOUND);
    }
}
