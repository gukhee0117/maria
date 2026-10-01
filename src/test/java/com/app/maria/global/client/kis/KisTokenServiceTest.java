package com.app.maria.global.client.kis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.app.maria.global.config.properties.PriceApiProperties;
import com.app.maria.global.error.AppException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class KisTokenServiceTest {

    private static final String TOKEN_KEY = "kis:access-token";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock RestTemplate restTemplate;

    @Mock StringRedisTemplate redisTemplate;

    @Mock ValueOperations<String, String> valueOperations;

    KisTokenService kisTokenService;

    @BeforeEach
    void setUp() {
        PriceApiProperties properties = new PriceApiProperties();
        properties.setUrl("https://openapivts.koreainvestment.com:29443");
        properties.setAppKey("test-app-key");
        properties.setAppSecret("test-app-secret");

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        kisTokenService = new KisTokenService(restTemplate, redisTemplate, properties);
    }

    @Test
    void getAccessToken_캐시된토큰이있으면_그대로반환하고_KIS는호출하지않는다() {
        when(valueOperations.get(TOKEN_KEY)).thenReturn("cached-token-value");

        String token = kisTokenService.getAccessToken();

        assertThat(token).isEqualTo("cached-token-value");
        verifyNoInteractions(restTemplate);
    }

    @Test
    void getAccessToken_캐시가없으면_KIS에서_새로발급받고_Redis에_TTL과함께_저장한다() throws Exception {
        when(valueOperations.get(TOKEN_KEY)).thenReturn(null);

        JsonNode response =
                objectMapper.readTree(
                        """
                {"access_token":"new-token-value","token_type":"Bearer","expires_in":86400}
                """);
        when(restTemplate.postForObject(anyString(), any(), eq(JsonNode.class)))
                .thenReturn(response);

        String token = kisTokenService.getAccessToken();

        assertThat(token).isEqualTo("new-token-value");

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(eq(TOKEN_KEY), eq("new-token-value"), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofSeconds(86400 - 300));
    }

    @Test
    void getAccessToken_KIS응답이_null이면_예외를던진다() {
        when(valueOperations.get(TOKEN_KEY)).thenReturn(null);
        when(restTemplate.postForObject(anyString(), any(), eq(JsonNode.class))).thenReturn(null);

        assertThatThrownBy(() -> kisTokenService.getAccessToken())
                .isInstanceOf(AppException.class);
    }

    @Test
    void getAccessToken_응답에_access_token이없으면_예외를던진다() throws Exception {
        when(valueOperations.get(TOKEN_KEY)).thenReturn(null);

        JsonNode response = objectMapper.readTree("{\"error\":\"invalid_client\"}");
        when(restTemplate.postForObject(anyString(), any(), eq(JsonNode.class)))
                .thenReturn(response);

        assertThatThrownBy(() -> kisTokenService.getAccessToken())
                .isInstanceOf(AppException.class);
    }

    @Test
    void getAccessToken_요청URL과_바디에_appkey_appsecret이_정확히들어간다() throws Exception {
        when(valueOperations.get(TOKEN_KEY)).thenReturn(null);

        JsonNode response = objectMapper.readTree("{\"access_token\":\"t\",\"expires_in\":86400}");

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        when(restTemplate.postForObject(
                        urlCaptor.capture(), entityCaptor.capture(), eq(JsonNode.class)))
                .thenReturn(response);

        kisTokenService.getAccessToken();

        assertThat(urlCaptor.getValue())
                .isEqualTo("https://openapivts.koreainvestment.com:29443/oauth2/tokenP");

        KisTokenRequest body = (KisTokenRequest) entityCaptor.getValue().getBody();
        assertThat(body.getGrantType()).isEqualTo("client_credentials");
        assertThat(body.getAppkey()).isEqualTo("test-app-key");
        assertThat(body.getAppsecret()).isEqualTo("test-app-secret");
    }
}
