package com.app.maria.global.client.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.config.properties.ExchangeApiProperties;
import com.app.maria.global.error.AppException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class ExchangeRateClientTest {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock RestTemplate restTemplate;

    @Mock BusinessClockService businessClockService;

    ExchangeRateClient exchangeRateClient;

    @BeforeEach
    void setUp() {
        ExchangeApiProperties properties = new ExchangeApiProperties();
        properties.setUrl("https://oapi.koreaexim.go.kr/site/program/financial/exchangeJSON?");
        properties.setApiKey("test-auth-key");
        exchangeRateClient = new ExchangeRateClient(restTemplate, properties, businessClockService);
        lenient().when(businessClockService.now()).thenReturn(LocalDateTime.now());
    }

    private JsonNode json(String content) throws Exception {
        return objectMapper.readTree(content);
    }

    private String buildUrl(LocalDate date) {
        return "https://oapi.koreaexim.go.kr/site/program/financial/exchangeJSON?authkey=test-auth-key&searchdate="
                + date.format(DATE_FORMAT)
                + "&data=AP01";
    }

    @Test
    void getBaseRate_통화를_찾으면_콤마를_제거하고_BigDecimal로_반환한다() throws Exception {
        JsonNode response =
                json(
                        """
                [
                  {"cur_unit":"AED","deal_bas_r":"390.33"},
                  {"cur_unit":"USD","deal_bas_r":"1,433.6"}
                ]
                """);
        when(restTemplate.getForObject(anyString(), eq(JsonNode.class))).thenReturn(response);

        BigDecimal rate = exchangeRateClient.getBaseRate("USD", LocalDate.now());

        assertThat(rate).isEqualByComparingTo("1433.6");
    }

    @Test
    void getBaseRate_요청URL에_authkey_searchdate_data파라미터가_들어간다() throws Exception {
        JsonNode response = json("[{\"cur_unit\":\"USD\",\"deal_bas_r\":\"1,433.6\"}]");
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        when(restTemplate.getForObject(urlCaptor.capture(), eq(JsonNode.class)))
                .thenReturn(response);

        LocalDate today = LocalDate.now();
        exchangeRateClient.getBaseRate("USD", today);

        assertThat(urlCaptor.getValue())
                .contains("authkey=test-auth-key")
                .contains("searchdate=" + today.format(DATE_FORMAT))
                .contains("data=AP01");
    }

    @Test
    void getBaseRate_응답이_null이면_예외를_던진다() {
        when(restTemplate.getForObject(anyString(), eq(JsonNode.class))).thenReturn(null);

        assertThatThrownBy(() -> exchangeRateClient.getBaseRate("USD", LocalDate.now()))
                .isInstanceOf(AppException.class);
    }

    @Test
    void getBaseRate_응답이_배열이_아니면_예외를_던진다() throws Exception {
        JsonNode response = json("{\"result\":2}");
        when(restTemplate.getForObject(anyString(), eq(JsonNode.class))).thenReturn(response);

        assertThatThrownBy(() -> exchangeRateClient.getBaseRate("USD", LocalDate.now()))
                .isInstanceOf(AppException.class);
    }

    @Test
    void getBaseRate_당일에_통화가_없으면_하루전날짜로_재조회한다() throws Exception {
        JsonNode empty = json("[]");
        JsonNode found = json("[{\"cur_unit\":\"USD\",\"deal_bas_r\":\"1,420.5\"}]");
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);

        when(restTemplate.getForObject(urlCaptor.capture(), eq(JsonNode.class)))
                .thenReturn(empty)
                .thenReturn(found);

        LocalDate today = LocalDate.now();
        BigDecimal rate = exchangeRateClient.getBaseRate("USD", today);

        assertThat(rate).isEqualByComparingTo("1420.5");
        assertThat(urlCaptor.getAllValues())
                .containsExactly(buildUrl(today), buildUrl(today.minusDays(1)));
    }

    @Test
    void getBaseRate_7일_넘게_못찾으면_예외를_던지고_정확히_8번만_조회한다() throws Exception {
        JsonNode empty = json("[]");
        when(restTemplate.getForObject(anyString(), eq(JsonNode.class))).thenReturn(empty);

        assertThatThrownBy(() -> exchangeRateClient.getBaseRate("USD", LocalDate.now()))
                .isInstanceOf(AppException.class);

        verify(restTemplate, times(8)).getForObject(anyString(), eq(JsonNode.class));
    }

    @Test
    @DisplayName("cur_unit이 JPY(100)이어도 JPY 조회에 매칭되고, 100으로 나눈 1엔당 환율을 반환한다")
    void getBaseRateMatchesJpyWithHundredUnitSuffixAndDividesRateByDenomination() throws Exception {
        JsonNode response =
                json(
                        """
                [
                  {"cur_unit":"USD","deal_bas_r":"1,433.6"},
                  {"cur_unit":"JPY(100)","deal_bas_r":"884.31"}
                ]
                """);
        when(restTemplate.getForObject(anyString(), eq(JsonNode.class))).thenReturn(response);

        BigDecimal rate = exchangeRateClient.getBaseRate("JPY", LocalDate.now());

        assertThat(rate).isEqualByComparingTo("8.8431");
    }

    @Test
    @DisplayName("cur_unit이 IDR(100)이어도 IDR 조회에 매칭되고, 100으로 나눈 1루피아당 환율을 반환한다")
    void getBaseRateMatchesIdrWithHundredUnitSuffixAndDividesRateByDenomination() throws Exception {
        JsonNode response = json("[{\"cur_unit\":\"IDR(100)\",\"deal_bas_r\":\"7.9\"}]");
        when(restTemplate.getForObject(anyString(), eq(JsonNode.class))).thenReturn(response);

        BigDecimal rate = exchangeRateClient.getBaseRate("IDR", LocalDate.now());

        assertThat(rate).isEqualByComparingTo("0.079");
    }

    @Test
    @DisplayName("통화코드가 부분일치할 뿐이면 매칭하지 않는다 (JP는 JPY(100)와 매칭되면 안 됨)")
    void getBaseRateDoesNotMatchOnPartialCurrencyCodePrefix() throws Exception {
        JsonNode empty = json("[{\"cur_unit\":\"JPY(100)\",\"deal_bas_r\":\"884.31\"}]");
        when(restTemplate.getForObject(anyString(), eq(JsonNode.class))).thenReturn(empty);

        assertThatThrownBy(() -> exchangeRateClient.getBaseRate("JP", LocalDate.now()))
                .isInstanceOf(AppException.class);
    }

    @Test
    void getBaseRate_날짜없는_오버로드는_오늘날짜로_조회한다() throws Exception {
        JsonNode response = json("[{\"cur_unit\":\"USD\",\"deal_bas_r\":\"1,433.6\"}]");
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        when(restTemplate.getForObject(urlCaptor.capture(), eq(JsonNode.class)))
                .thenReturn(response);

        exchangeRateClient.getBaseRate("USD");

        assertThat(urlCaptor.getValue())
                .contains("searchdate=" + LocalDate.now().format(DATE_FORMAT));
    }
}
