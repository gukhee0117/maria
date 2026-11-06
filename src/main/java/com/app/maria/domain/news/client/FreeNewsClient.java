package com.app.maria.domain.news.client;

import com.app.maria.domain.news.config.NewsProperties;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Only the fixed provider host is contacted. Article URLs are never fetched or crawled. */
@Component
public class FreeNewsClient {
    private final RestClient client;
    private final NewsProperties properties;
    private long nextRequestNanos;

    @Autowired
    public FreeNewsClient(NewsProperties properties) {
        this.properties = properties;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(8));
        client =
                RestClient.builder()
                        .baseUrl("https://freenewsapi.ai")
                        .requestFactory(factory)
                        .build();
    }

    FreeNewsClient(NewsProperties properties, RestClient client) {
        this.properties = properties;
        this.client = client;
    }

    public JsonNode search(String stockName) {
        if (properties.getFreeNewsApiKey().isBlank()
                || properties.getFreeNewsApiKey().chars().anyMatch(ch -> ch <= 32 || ch >= 127))
            throw new AppException(ErrorType.NEWS_SOURCE_NOT_CONFIGURED);
        try {
            pace();
            JsonNode response =
                    client.get()
                            .uri(
                                    b ->
                                            b.path("/v1/search")
                                                    .queryParam("q", "{query}")
                                                    .queryParam("lang", "ko")
                                                    .queryParam("country", "KR")
                                                    .queryParam("date", "7d")
                                                    .queryParam("size", 10)
                                                    .queryParam("sort", "relevance")
                                                    .queryParam("full_text", "true")
                                                    .build(stockName))
                            .header("Authorization", "Bearer " + properties.getFreeNewsApiKey())
                            .header("Accept", "application/json")
                            .header("User-Agent", "MARIA-News-Dev/1.0")
                            .retrieve()
                            .body(JsonNode.class);
            if (response == null || !response.path("results").isArray()) throw unavailable();
            // All unrelated metadata, including promotional 'strongly_recommended', is ignored.
            return response.path("results");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403)
                throw new AppException(ErrorType.NEWS_SOURCE_AUTH_FAILED);
            if (status == 429) throw new AppException(ErrorType.NEWS_SOURCE_RATE_LIMITED);
            throw unavailable();
        } catch (RuntimeException e) {
            if (e instanceof AppException app) throw app;
            throw unavailable();
        }
    }

    private synchronized void pace() throws InterruptedException {
        long delay = nextRequestNanos - System.nanoTime();
        if (delay > 0) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(delay);
        nextRequestNanos = System.nanoTime() + Duration.ofMillis(600).toNanos();
    }

    private AppException unavailable() {
        return new AppException(ErrorType.NEWS_UPSTREAM_UNAVAILABLE);
    }
}
