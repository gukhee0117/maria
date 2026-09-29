package com.app.maria.global.client.kis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.app.maria.global.config.properties.PriceApiProperties;
import com.app.maria.global.exception.KisPriceNotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class KisPriceClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock RestTemplate restTemplate;

    @Mock KisTokenService kisTokenService;

    KisPriceClient kisPriceClient;

    @BeforeEach
    void setUp() {
        PriceApiProperties properties = new PriceApiProperties();
        properties.setUrl("https://openapivts.koreainvestment.com:29443");
        properties.setAppKey("test-app-key");
        properties.setAppSecret("test-app-secret");

        kisPriceClient = new KisPriceClient(restTemplate, kisTokenService, properties);
    }

    private JsonNode json(String content) throws Exception {
        return objectMapper.readTree(content);
    }

    @Test
    void getPreviousClose_성공응답이면_output의_base값을_BigDecimal로_반환한다() throws Exception {
        when(kisTokenService.getAccessToken()).thenReturn("token-value");
        JsonNode response =
                json(
                        """
                {"output":{"base":"308.9100"},"rt_cd":"0","msg1":"정상처리 되었습니다."}
                """);
        when(restTemplate.exchange(
                        anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class)))
                .thenReturn(new ResponseEntity<>(response, HttpStatus.OK));

        BigDecimal base = kisPriceClient.getPreviousClose("NAS", "AAPL");

        assertThat(base).isEqualByComparingTo("308.9100");
    }

    @Test
    void getPreviousClose_응답이_null이면_예외를던진다() {
        when(kisTokenService.getAccessToken()).thenReturn("token-value");
        when(restTemplate.exchange(
                        anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class)))
                .thenReturn(new ResponseEntity<>(null, HttpStatus.OK));

        assertThatThrownBy(() -> kisPriceClient.getPreviousClose("NAS", "AAPL"))
                .isInstanceOf(KisPriceNotFoundException.class);
    }

    @Test
    void getPreviousClose_rt_cd가_0이아니면_예외를던진다() throws Exception {
        when(kisTokenService.getAccessToken()).thenReturn("token-value");
        JsonNode response =
                json(
                        """
                {"rt_cd":"1","msg1":"실전투자 도메인은 모의투자 앱키로 호출하실 수 없습니다.","msg_cd":"EGW02004"}
                """);
        when(restTemplate.exchange(
                        anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class)))
                .thenReturn(new ResponseEntity<>(response, HttpStatus.OK));

        assertThatThrownBy(() -> kisPriceClient.getPreviousClose("NAS", "AAPL"))
                .isInstanceOf(KisPriceNotFoundException.class);
    }

    @Test
    void getPreviousClose_요청헤더에_토큰과_appkey_appsecret_trid가_정확히들어간다() throws Exception {
        when(kisTokenService.getAccessToken()).thenReturn("my-access-token");
        JsonNode response = json("{\"output\":{\"base\":\"1\"},\"rt_cd\":\"0\"}");

        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        when(restTemplate.exchange(
                        anyString(),
                        eq(HttpMethod.GET),
                        entityCaptor.capture(),
                        eq(JsonNode.class)))
                .thenReturn(new ResponseEntity<>(response, HttpStatus.OK));

        kisPriceClient.getPreviousClose("NAS", "AAPL");

        HttpHeaders headers = entityCaptor.getValue().getHeaders();
        assertThat(headers.getFirst("authorization")).isEqualTo("Bearer my-access-token");
        assertThat(headers.getFirst("appkey")).isEqualTo("test-app-key");
        assertThat(headers.getFirst("appsecret")).isEqualTo("test-app-secret");
        assertThat(headers.getFirst("tr_id")).isEqualTo("HHDFS00000300");
        assertThat(headers.getFirst("custtype")).isEqualTo("P");
    }

    @Test
    void getPreviousClose_요청URL에_거래소코드와_종목코드가_들어간다() throws Exception {
        when(kisTokenService.getAccessToken()).thenReturn("token-value");
        JsonNode response = json("{\"output\":{\"base\":\"1\"},\"rt_cd\":\"0\"}");

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        when(restTemplate.exchange(
                        urlCaptor.capture(),
                        eq(HttpMethod.GET),
                        any(HttpEntity.class),
                        eq(JsonNode.class)))
                .thenReturn(new ResponseEntity<>(response, HttpStatus.OK));

        kisPriceClient.getPreviousClose("NAS", "AAPL");

        assertThat(urlCaptor.getValue())
                .contains("EXCD=NAS")
                .contains("SYMB=AAPL")
                .contains("AUTH=");
    }

    private HttpServerErrorException rateLimitError() {
        String body = "{\"rt_cd\":\"1\",\"msg1\":\"초당 거래건수를 초과하였습니다.\",\"msg_cd\":\"EGW00201\"}";
        return HttpServerErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Server Error",
                new HttpHeaders(),
                body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    @Test
    void getPreviousClose_초당거래건수초과면_재시도해서_성공한다() throws Exception {
        when(kisTokenService.getAccessToken()).thenReturn("token-value");
        JsonNode success = json("{\"output\":{\"base\":\"100\"},\"rt_cd\":\"0\"}");
        when(restTemplate.exchange(
                        anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class)))
                .thenThrow(rateLimitError())
                .thenReturn(new ResponseEntity<>(success, HttpStatus.OK));

        BigDecimal base = kisPriceClient.getPreviousClose("NAS", "AAPL");

        assertThat(base).isEqualByComparingTo("100");
    }

    @Test
    void getPreviousClose_초당거래건수초과가_계속되면_결국_예외를던진다() {
        when(kisTokenService.getAccessToken()).thenReturn("token-value");
        when(restTemplate.exchange(
                        anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class)))
                .thenThrow(rateLimitError());

        assertThatThrownBy(() -> kisPriceClient.getPreviousClose("NAS", "AAPL"))
                .isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void getPreviousClose_레이트리밋이_아닌_5xx는_바로던진다() {
        when(kisTokenService.getAccessToken()).thenReturn("token-value");
        HttpServerErrorException otherError =
                HttpServerErrorException.create(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Internal Server Error",
                        new HttpHeaders(),
                        "{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00002\"}".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8);
        when(restTemplate.exchange(
                        anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class)))
                .thenThrow(otherError);

        assertThatThrownBy(() -> kisPriceClient.getPreviousClose("NAS", "AAPL"))
                .isSameAs(otherError);
    }
}
