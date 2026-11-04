package com.app.maria.global.client.kis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.app.maria.global.config.properties.PriceApiProperties;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class KisDomesticPriceClientTest {

    private static final String URL =
            "https://kis.test/uapi/domestic-stock/v1/quotations/inquire-price"
                    + "?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=005930";

    private MockRestServiceServer server;
    private KisDomesticPriceClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        KisTokenService token = mock(KisTokenService.class);
        when(token.getAccessToken()).thenReturn("tok");
        PriceApiProperties props = new PriceApiProperties();
        props.setUrl("https://kis.test");
        props.setAppKey("key");
        props.setAppSecret("secret");
        client = new KisDomesticPriceClient(restTemplate, token, props);
    }

    private static String body(String rtCd, String prpr) {
        return "{\"rt_cd\":\""
                + rtCd
                + "\",\"output\":{\"stck_prpr\":\""
                + prpr
                + "\",\"stck_sdpr\":\"97500\",\"stck_mxpr\":\"126750\",\"stck_llam\":\"68250\"}}";
    }

    @Test
    void 현재가와_기준가_상하한가를_읽는다() {
        server.expect(requestTo(URL))
                .andExpect(header("tr_id", "FHKST01010100"))
                .andExpect(header("authorization", "Bearer tok"))
                .andRespond(withSuccess(body("0", "98000"), MediaType.APPLICATION_JSON));

        DomesticQuote q = client.getQuote("005930");

        assertThat(q.price()).isEqualByComparingTo("98000");
        assertThat(q.previousClose()).isEqualByComparingTo("97500");
        assertThat(q.upperLimit()).isEqualByComparingTo("126750");
        assertThat(q.lowerLimit()).isEqualByComparingTo("68250");
        server.verify();
    }

    @Test
    void rt_cd가_0이_아니면_시세없음() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess(body("1", "98000"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getQuote("005930"))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.KIS_PRICE_NOT_FOUND));
    }

    @Test
    void 현재가가_0이거나_비었으면_시세없음() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess(body("0", "0"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.getQuote("005930")).isInstanceOf(AppException.class);

        server.reset();
        server.expect(requestTo(URL))
                .andRespond(withSuccess(body("0", ""), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.getQuote("005930")).isInstanceOf(AppException.class);
    }

    @Test
    void 호출제한이면_재시도해서_성공한다() {
        server.expect(requestTo(URL))
                .andRespond(withServerError().body("{\"msg_cd\":\"EGW00201\"}"));
        server.expect(requestTo(URL))
                .andExpect(r -> assertThat(r.getMethod()).isEqualTo(HttpMethod.GET))
                .andRespond(withSuccess(body("0", "98000"), MediaType.APPLICATION_JSON));

        assertThat(client.getQuote("005930").price()).isEqualByComparingTo(new BigDecimal("98000"));
        server.verify();
    }

    @Test
    void 호출제한이_아닌_서버오류는_재시도하지_않고_시세없음() {
        server.expect(requestTo(URL)).andRespond(withServerError().body("{\"msg_cd\":\"X\"}"));

        assertThatThrownBy(() -> client.getQuote("005930")).isInstanceOf(AppException.class);
        server.verify();
    }
}
