package com.app.maria.domain.news.client;

import com.app.maria.domain.news.config.NewsProperties;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Display-only route: no AI service/provider dependencies, transformations or caching. */
@Component
public class NaverNewsSearchClient {
    private final RestClient client;
    private final NewsProperties properties;

    @org.springframework.beans.factory.annotation.Autowired
    public NaverNewsSearchClient(NewsProperties properties) {
        this.properties = properties;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        client =
                RestClient.builder()
                        .baseUrl("https://naverapihub.apigw.ntruss.com")
                        .requestFactory(factory)
                        .build();
    }

    NaverNewsSearchClient(NewsProperties properties, RestClient client) {
        this.properties = properties;
        this.client = client;
    }

    public JsonNode search(String query) {
        if (properties.getNaverClientId().isBlank() || properties.getNaverClientSecret().isBlank())
            throw new AppException(ErrorType.NEWS_SEARCH_UNAVAILABLE);
        try {
            JsonNode response =
                    client.get()
                            .uri(
                                    b ->
                                            b.path("/search/v1/news")
                                                    .queryParam("query", query)
                                                    .queryParam("display", 10)
                                                    .queryParam("start", 1)
                                                    .queryParam("sort", "sim")
                                                    .queryParam("format", "json")
                                                    .build())
                            .header("X-NCP-APIGW-API-KEY-ID", properties.getNaverClientId())
                            .header("X-NCP-APIGW-API-KEY", properties.getNaverClientSecret())
                            .retrieve()
                            .body(JsonNode.class);
            if (response == null || !response.path("items").isArray())
                throw new IllegalStateException();
            return response;
        } catch (RuntimeException e) {
            throw new AppException(ErrorType.NEWS_SEARCH_UNAVAILABLE);
        }
    }
}
