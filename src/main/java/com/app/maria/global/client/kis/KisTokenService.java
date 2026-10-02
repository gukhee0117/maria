package com.app.maria.global.client.kis;

import com.app.maria.global.config.properties.PriceApiProperties;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
@RequiredArgsConstructor
public class KisTokenService {

    private final RestTemplate restTemplate;
    private final StringRedisTemplate redisTemplate;
    private final PriceApiProperties priceApiProperties;

    private static final String TOKEN_KEY = "kis:access-token"; // redis에 토큰 저장할 때 key 이름
    private static final long EXPIRY_BUFFER_SECONDS = 300; // 실제 만료보다 5분 일찍 사라지게 함

    // 진입점, redis에 캐시된 토큰 있으면 반환 / 없으면 새로 발급
    public String getAccessToken() {
        String cachedToken = redisTemplate.opsForValue().get(TOKEN_KEY);
        if (cachedToken != null) {
            return cachedToken;
        }
        return issueAndCacheToken();
    }

    // KIS 토큰 발급 API 호출
    private String issueAndCacheToken() {
        String url = priceApiProperties.getUrl() + "/oauth2/tokenP";

        // body 담아가는 data
        KisTokenRequest body =
                new KisTokenRequest(
                        "client_credentials",
                        priceApiProperties.getAppKey(),
                        priceApiProperties.getAppSecret());

        // json 담아간다고 header에 적음
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // header와 body 하나도 합쳐줌
        HttpEntity<KisTokenRequest> request = new HttpEntity<>(body, headers);

        JsonNode response = restTemplate.postForObject(url, request, JsonNode.class);

        if (response == null || !response.hasNonNull("access_token")) {
            throw new AppException(ErrorType.KIS_TOKEN_ISSUE);
        }

        String accessToken = response.path("access_token").asText();
        long expiresIn = response.path("expires_in").asLong();

        redisTemplate
                .opsForValue()
                .set(TOKEN_KEY, accessToken, Duration.ofSeconds(expiresIn - EXPIRY_BUFFER_SECONDS));

        return accessToken;
    }
}
