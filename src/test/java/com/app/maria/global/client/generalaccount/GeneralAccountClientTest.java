package com.app.maria.global.client.generalaccount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.app.maria.global.client.generalaccount.dto.request.GeneralAccountRequestDTO;
import com.app.maria.global.client.generalaccount.dto.response.GeneralAccountResponseDTO;
import com.app.maria.global.client.generalaccount.type.GeneralAccountStatus;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import com.app.maria.global.exception.GeneralAccountApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GeneralAccountClientTest {

    private static final String BASE_URL = "http://localhost:10001";
    private static final String VERIFY_URL = BASE_URL + "/api/general-accounts/verify";

    private MockRestServiceServer mockServer;
    private GeneralAccountClient generalAccountClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        generalAccountClient = new GeneralAccountClient(builder.build());
    }

    @Test
    @DisplayName("일반계좌 검증 요청의 경로, 방식, 식별값과 성공 응답을 검증한다")
    void verifyGeneralAccountSendsRequestAndReturnsVerifiedAccount() {
        mockServer
                .expect(requestTo(VERIFY_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().string(containsString("\"ciHash\":\"ci-hash-1\"")))
                .andExpect(content().string(containsString("\"generalAccountId\":31")))
                .andRespond(
                        withSuccess(
                                """
                        {
                          "message": "일반계좌 검증 성공",
                          "data": {
                            "generalAccountId": 31,
                            "accountNo": "1234567890",
                            "status": "ACTIVE"
                          }
                        }
                        """,
                                MediaType.APPLICATION_JSON));

        GeneralAccountResponseDTO result = generalAccountClient.verifyGeneralAccount(request());

        assertThat(result.getGeneralAccountId()).isEqualTo(31L);
        assertThat(result.getAccountNo()).isEqualTo("1234567890");
        assertThat(result.getStatus()).isEqualTo(GeneralAccountStatus.ACTIVE);
        mockServer.verify();
    }

    @Test
    @DisplayName("증권사 API가 4xx를 반환하면 인출 불가 예외로 변환한다")
    void verifyGeneralAccountConvertsClientErrorToWithdrawalNotAllowed() {
        mockServer
                .expect(requestTo(VERIFY_URL))
                .andRespond(
                        withStatus(HttpStatus.BAD_REQUEST)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body("{\"message\":\"해지된 일반계좌입니다.\",\"data\":null}"));

        assertThatThrownBy(() -> generalAccountClient.verifyGeneralAccount(request()))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.WITHDRAWAL_NOT_ALLOWED.getMessage());
        mockServer.verify();
    }

    @Test
    @DisplayName("증권사 API가 5xx를 반환하면 외부 API 예외로 변환하고 원인을 보존한다")
    void verifyGeneralAccountConvertsServerErrorToApiException() {
        mockServer
                .expect(requestTo(VERIFY_URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> generalAccountClient.verifyGeneralAccount(request()))
                .isInstanceOf(GeneralAccountApiException.class)
                .hasMessage("증권사 일반계좌 검증 API 호출에 실패했습니다.")
                .hasCauseInstanceOf(org.springframework.web.client.HttpServerErrorException.class);
        mockServer.verify();
    }

    @Test
    @DisplayName("정상 상태 응답이어도 data가 null이면 외부 API 예외를 발생시킨다")
    void verifyGeneralAccountRejectsEmptyData() {
        mockServer
                .expect(requestTo(VERIFY_URL))
                .andRespond(
                        withSuccess(
                                """
                        {"message":"조회 성공","data":null}
                        """,
                                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> generalAccountClient.verifyGeneralAccount(request()))
                .isInstanceOf(GeneralAccountApiException.class)
                .hasMessage("인출 목적지 일반계좌를 확인할 수 없습니다.");
        mockServer.verify();
    }

    private GeneralAccountRequestDTO request() {
        return GeneralAccountRequestDTO.builder().ciHash("ci-hash-1").generalAccountId(31L).build();
    }
}
