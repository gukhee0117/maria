package com.app.maria.global.client.domestictrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.app.maria.domain.domestic.dto.DomesticTradeHistoryDTO;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DomesticTradeClientTest {

    private static final String URL = "http://localhost:10001/api/domestic-trades";

    private MockRestServiceServer mockServer;
    private DomesticTradeClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:10001");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new DomesticTradeClient(builder.build());
    }

    @Test
    @DisplayName("ciHash로 증권사에 POST하고 체결 내역을 받는다")
    void sendsCiHashAndParsesTrades() {
        mockServer
                .expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("\"ciHash\":\"ci-1\"")))
                .andRespond(
                        withSuccess(
                                """
                                {"message":"조회 성공","data":[
                                  {"tradeType":"BUY","stockCode":"005930","qty":10,"price":70000,
                                   "executedAt":"2026-11-03T10:00:00"}]}
                                """,
                                MediaType.APPLICATION_JSON));

        List<DomesticTradeHistoryDTO> result = client.findByCiHash("ci-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStockCode()).isEqualTo("005930");
        assertThat(result.get(0).getTradeType()).isEqualTo("BUY");
        assertThat(result.get(0).getQty()).isEqualByComparingTo("10");
        mockServer.verify();
    }

    @Test
    @DisplayName("200인데 data가 null이면 빈 목록이 아니라 DOMESTIC_TRADE_API_INVALID_RESPONSE다")
    void nullDataIsInvalidResponse() {
        mockServer
                .expect(requestTo(URL))
                .andRespond(
                        withSuccess(
                                "{\"message\":\"조회 성공\",\"data\":null}",
                                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.findByCiHash("ci-1"))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.DOMESTIC_TRADE_API_INVALID_RESPONSE.getMessage());
    }

    @Test
    @DisplayName("정상 응답의 빈 배열은 체결 없음(빈 목록)이다")
    void emptyArrayIsEmptyList() {
        mockServer
                .expect(requestTo(URL))
                .andRespond(
                        withSuccess(
                                "{\"message\":\"조회 성공\",\"data\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.findByCiHash("ci-1")).isEmpty();
    }

    @Test
    @DisplayName("실패 예외에 ciHash가 담기지 않는다(오류 로그 노출 방지)")
    void failureDoesNotCarryCiHash() {
        mockServer.expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.findByCiHash("ci-secret-hash"))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(String.valueOf(e.getErrorData()))
                                        .doesNotContain("ci-secret-hash"));
    }

    @Test
    @DisplayName("증권사 호출이 실패하면 빈 목록이 아니라 DOMESTIC_TRADE_API_UNAVAILABLE이다")
    void failureIsNotSwallowed() {
        mockServer.expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.findByCiHash("ci-1"))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.DOMESTIC_TRADE_API_UNAVAILABLE.getMessage());
    }
}
