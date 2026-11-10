package com.app.maria.domain.news.client;

import com.app.maria.domain.news.config.NewsProperties;
import com.app.maria.domain.news.mapper.NewsSourceUsageMapper;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Only the fixed provider host is contacted. Article URLs are never fetched or crawled. */
@Component
public class FreeNewsClient {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final RestClient client;
    private final NewsProperties properties;
    private final NewsSourceUsageMapper usageMapper;
    private final BusinessClockService businessClock;
    private final Clock clock;
    private long nextRequestNanos;
    private java.time.YearMonth requestMonth;
    private int requestsThisMonth;

    @Autowired
    public FreeNewsClient(
            NewsProperties properties,
            NewsSourceUsageMapper usageMapper,
            BusinessClockService businessClock,
            Clock clock) {
        this(properties, createClient(), usageMapper, businessClock, clock);
    }

    public record SearchWindow(Instant from, Instant to, String cacheKey) {}

    public static SearchWindow window(LocalDate businessDate, Instant now) {
        LocalDate today = now.atZone(SEOUL).toLocalDate();
        LocalDate effective = businessDate.isBefore(today) ? businessDate : today;
        Instant end =
                effective.isBefore(today)
                        ? effective.plusDays(1).atStartOfDay(SEOUL).toInstant()
                        : now;
        Instant start = effective.minusDays(6).atStartOfDay(SEOUL).toInstant();
        return new SearchWindow(
                start, end, effective + (effective.isBefore(today) ? ":closed" : ":live"));
    }

    /**
     * Capture once per collection/read. The live suffix partitions dates, not freshness: current
     * and historical dates both retain the provider TTL and API request budget.
     */
    public SearchWindow searchWindow() {
        Instant now = clock.instant();
        LocalDate day = businessClock.now().toLocalDate();
        return window(day, now);
    }

    public String cacheVersion() {
        return searchWindow().cacheKey();
    }

    FreeNewsClient(
            NewsProperties properties,
            RestClient client,
            NewsSourceUsageMapper usageMapper,
            BusinessClockService businessClock,
            Clock clock) {
        this.properties = properties;
        this.client = client;
        this.usageMapper = usageMapper;
        this.businessClock = Objects.requireNonNull(businessClock);
        this.clock = Objects.requireNonNull(clock);
    }

    private static RestClient createClient() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(8));
        return RestClient.builder()
                .baseUrl("https://freenewsapi.ai")
                .requestFactory(factory)
                .build();
    }

    public JsonNode search(String stockName) {
        return search(stockName, searchWindow());
    }

    public JsonNode search(String stockName, SearchWindow window) {
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
                                                    .queryParam("from", window.from().toString())
                                                    .queryParam("to", window.to().toString())
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

    private void pace() throws InterruptedException {
        long delay;
        synchronized (this) {
            var month = java.time.YearMonth.now(java.time.Clock.systemUTC());
            if (usageMapper == null) {
                // In-memory fallback is only for package-private tests without a database mapper.
                if (!month.equals(requestMonth)) {
                    requestMonth = month;
                    requestsThisMonth = 0;
                }
                if (requestsThisMonth >= properties.getSourceMonthlyRequestLimit())
                    throw new AppException(ErrorType.NEWS_SOURCE_RATE_LIMITED);
                requestsThisMonth++;
            } else {
                String usageMonth = month.toString();
                usageMapper.ensureMonth(usageMonth);
                if (usageMapper.reserveRequest(
                                usageMonth, properties.getSourceMonthlyRequestLimit())
                        != 1) {
                    throw new AppException(ErrorType.NEWS_SOURCE_RATE_LIMITED);
                }
            }
            long now = System.nanoTime();
            delay = nextRequestNanos - now;
            nextRequestNanos = Math.max(now, nextRequestNanos) + Duration.ofMillis(600).toNanos();
        }
        // Production callers are FreeNewsProvider's bounded news-source-reader workers.
        if (delay > 0) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(delay);
    }

    private AppException unavailable() {
        return new AppException(ErrorType.NEWS_UPSTREAM_UNAVAILABLE);
    }
}
