package com.app.maria.global.client.registrablestock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.app.maria.domain.registrablestock.dto.RegistrableStockResponseDTO;
import com.app.maria.domain.registrablestock.type.GeneralAccountType;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RegistrableStockClientTest {
    private static final String URL =
            "http://localhost:10001/api/registrable-stocks/customer-lots?ciHash=test-ci";
    private MockRestServiceServer server;
    private RegistrableStockClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:10001");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RegistrableStockClient(builder.build());
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    @DisplayName("고객 전체 원본 조회 경로와 새 식별자·매입 정보를 역직렬화한다")
    void readsCustomerLotResponse() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(
                        withSuccess(
                                """
                {
                  "message": "조회 성공",
                  "data": [{
                    "registrableStockId": 777,
                    "foreignProductId": 1,
                    "generalAccountId": 10,
                    "accountType": "BROKERAGE",
                    "heldQty": 100.1234,
                    "sourceBroker": null,
                    "purchaseDate": "2025-01-01T10:00:00",
                    "purchasePrice": 180.1234,
                    "purchaseCurrency": "USD",
                    "purchaseFxRate": 1380.5678
                  }]
                }
                """,
                                MediaType.APPLICATION_JSON));

        List<RegistrableStockResponseDTO> result = client.findCustomerLots("test-ci");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getRegistrableStockId()).isEqualTo(777L);
        assertThat(result.get(0).getForeignProductId()).isEqualTo(1L);
        assertThat(result.get(0).getGeneralAccountId()).isEqualTo(10L);
        assertThat(result.get(0).getAccountType()).isEqualTo(GeneralAccountType.BROKERAGE);
        assertThat(result.get(0).getHeldQty()).isEqualByComparingTo("100.1234");
        assertThat(result.get(0).getPurchaseDate()).isEqualTo(LocalDateTime.of(2025, 1, 1, 10, 0));
        assertThat(result.get(0).getPurchaseFxRate()).isEqualByComparingTo("1380.5678");
    }

    @Test
    @DisplayName("data 빈 배열은 정상적인 빈 결과로 반환한다")
    void acceptsEmptyList() {
        server.expect(requestTo(URL))
                .andRespond(
                        withSuccess(
                                "{\"message\":\"조회 성공\",\"data\":[]}", MediaType.APPLICATION_JSON));
        assertThat(client.findCustomerLots("test-ci")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"data\":null}"})
    @DisplayName("data 누락과 null은 정상 빈 목록과 구분한다")
    void rejectsMissingData(String body) {
        server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertLookupFailed();
    }

    @Test
    @DisplayName("본문이 없는 응답은 잘못된 조회 응답으로 처리한다")
    void rejectsNoContent() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NO_CONTENT));
        assertLookupFailed();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"data\":{}}"})
    @DisplayName("잘못된 JSON과 배열이 아닌 data를 조회 오류로 변환한다")
    void rejectsMalformedResponse(String body) {
        server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertLookupFailed();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 500, 503})
    @DisplayName("외부 HTTP 오류를 기존 Client 조회 오류로 변환한다")
    void convertsHttpErrors(int status) {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.valueOf(status)));
        assertLookupFailed();
    }

    @Test
    @DisplayName("통신 타임아웃은 조회 오류로 변환하고 자동 재시도하지 않는다")
    void convertsTransportErrorWithoutRetry() {
        server.expect(requestTo(URL))
                .andRespond(
                        request -> {
                            throw new SocketTimeoutException("test timeout");
                        });
        assertLookupFailed();
    }

    private void assertLookupFailed() {
        // 고객 API의 503 응답 변환은 4단계에서 연결하고 현재 Client 계약은 유지한다.
        assertThatThrownBy(() -> client.findCustomerLots("test-ci"))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception ->
                                assertThat(exception.getErrorType())
                                        .isEqualTo(ErrorType.REGISTRABLE_STOCK_NOT_FOUND));
    }
}
