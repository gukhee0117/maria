package com.app.maria.global.client.mydata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.app.maria.global.config.properties.MydataApiProperties;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class MydataClientTest {

    private static final String MYDATA_URL = "http://mydata.test";
    private static final String RIA_ACCOUNTS_URL = MYDATA_URL + "/api/mydata/ria-accounts";

    private MockRestServiceServer mockServer;
    private MydataClient mydataClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(MYDATA_URL);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        MydataApiProperties properties = new MydataApiProperties();
        properties.setOwnBrokerName("리턴증권");
        mydataClient = new MydataClient(builder.build(), properties);
    }

    private void respondAccounts(String accountsJson) {
        mockServer
                .expect(requestTo(RIA_ACCOUNTS_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(
                        withSuccess(
                                "{\"message\":\"성공\",\"data\":" + accountsJson + "}",
                                MediaType.APPLICATION_JSON));
    }

    private static String account(String brokerName, String riaLimit, String cumulativeSell) {
        return "{\"brokerName\":\"%s\",\"riaLimit\":%s,\"riaCumulativeSell\":%s}"
                .formatted(brokerName, riaLimit, cumulativeSell);
    }

    @Test
    void getExternalSellTotal_우리증권사를_빼고_타사_누적매도만_더한다() {
        respondAccounts(
                "["
                        + account("증권사A", "20000000", "1000000")
                        + ","
                        + account("리턴증권", "30000000", "9999999")
                        + ","
                        + account("증권사B", "10000000", "2000000")
                        + "]");

        assertThat(mydataClient.getExternalSellTotal("ci-hash")).isEqualByComparingTo("3000000");
        mockServer.verify();
    }

    @Test
    void getExternalConfiguredLimit_우리증권사를_빼고_타사_설정한도만_더한다() {
        respondAccounts(
                "["
                        + account("증권사A", "20000000", "0")
                        + ","
                        + account("리턴증권", "30000000", "0")
                        + "]");

        assertThat(mydataClient.getExternalConfiguredLimit("ci-hash"))
                .isEqualByComparingTo("20000000");
    }

    @Test
    void 타사_계좌가_없으면_0이다() {
        respondAccounts("[]");

        assertThat(mydataClient.getExternalSellTotal("ci-hash")).isEqualByComparingTo("0");
    }

    @Test
    void 요청바디에_ciHash가_들어간다() {
        mockServer
                .expect(requestTo(RIA_ACCOUNTS_URL))
                .andExpect(content().string(containsString("\"ciHash\":\"ci-hash-xyz\"")))
                .andRespond(
                        withSuccess(
                                "{\"message\":\"성공\",\"data\":[]}", MediaType.APPLICATION_JSON));

        mydataClient.getExternalSellTotal("ci-hash-xyz");

        mockServer.verify();
    }

    @Test
    void 타사_금액이_null이거나_음수면_INVALID_RESPONSE() {
        respondAccounts("[" + account("증권사A", "20000000", "null") + "]");
        assertThatThrownBy(() -> mydataClient.getExternalSellTotal("ci-hash"))
                .isInstanceOf(AppException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.MYDATA_API_INVALID_RESPONSE);

        mockServer.reset();
        respondAccounts("[" + account("증권사A", "-1", "0") + "]");
        assertThatThrownBy(() -> mydataClient.getExternalConfiguredLimit("ci-hash"))
                .isInstanceOf(AppException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.MYDATA_API_INVALID_RESPONSE);
    }

    @Test
    void 응답_data가_null이면_INVALID_RESPONSE() {
        respondAccounts("null");

        assertThatThrownBy(() -> mydataClient.getExternalSellTotal("ci-hash"))
                .isInstanceOf(AppException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.MYDATA_API_INVALID_RESPONSE);
    }

    @Test
    void 응답_목록에_null_원소가_있으면_INVALID_RESPONSE() {
        respondAccounts("[null]");

        assertThatThrownBy(() -> mydataClient.getExternalSellTotal("ci-hash"))
                .isInstanceOf(AppException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.MYDATA_API_INVALID_RESPONSE);
    }

    @Test
    void 우리증권사_행의_금액이_비정상이어도_무시한다() {
        respondAccounts("[" + account("리턴증권", "null", "null") + "]");

        assertThat(mydataClient.getExternalSellTotal("ci-hash")).isEqualByComparingTo("0");
    }

    @Test
    void 서버오류_404_모두_UNAVAILABLE로_던진다() {
        mockServer.expect(requestTo(RIA_ACCOUNTS_URL)).andRespond(withServerError());
        assertThatThrownBy(() -> mydataClient.getExternalSellTotal("ci-hash"))
                .isInstanceOf(AppException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.MYDATA_API_UNAVAILABLE);

        mockServer.reset();
        mockServer.expect(requestTo(RIA_ACCOUNTS_URL)).andRespond(withResourceNotFound());
        assertThatThrownBy(() -> mydataClient.getExternalConfiguredLimit("ci-hash"))
                .isInstanceOf(AppException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.MYDATA_API_UNAVAILABLE);
    }

    @Test
    void syncRiaAccount_우리증권사명과_한도만_보내고_누적매도는_안보낸다() {
        mockServer
                .expect(requestTo(RIA_ACCOUNTS_URL + "/save"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(containsString("\"ciHash\":\"ci-hash\"")))
                .andExpect(content().string(containsString("\"brokerName\":\"리턴증권\"")))
                .andExpect(content().string(containsString("\"riaLimit\":\"40000000\"")))
                .andExpect(content().string(not(containsString("riaCumulativeSell"))))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        mydataClient.syncRiaAccount("ci-hash", new BigDecimal("40000000"));
        mockServer.verify();
    }

    @Test
    void syncRiaAccount_실패하면_UNAVAILABLE로_던진다() {
        mockServer.expect(requestTo(RIA_ACCOUNTS_URL + "/save")).andRespond(withServerError());

        assertThatThrownBy(() -> mydataClient.syncRiaAccount("ci-hash", BigDecimal.TEN))
                .isInstanceOf(AppException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.MYDATA_API_UNAVAILABLE);
    }
}
