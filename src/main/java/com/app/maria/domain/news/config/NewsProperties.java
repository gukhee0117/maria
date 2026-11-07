package com.app.maria.domain.news.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** NEWS_* environment variables bind through Spring's relaxed property binding. */
@Component
@ConfigurationProperties(prefix = "news")
@Getter
@Setter
public class NewsProperties {
    private boolean liveSummaries = true;
    private boolean liveQuestions = true;
    private int questionMaxRequestsPerMinute = 20;
    private String naverClientId = "";
    private String naverClientSecret = "";
    private String model = "gpt-4o-mini";
    private String provider = "free";
    private String freeNewsApiKey = "";
    private java.time.Duration summaryCacheTtl = java.time.Duration.ofHours(24);
    private int summaryCacheMaxEntries = 1024;
    private java.time.Duration sourceCacheTtl = java.time.Duration.ofHours(2);
    private java.time.Duration inventoryCacheTtl = java.time.Duration.ofMinutes(30);
    private int sourceMonthlyRequestLimit = 2400;
    private boolean prewarmEnabled = true;
    private int prewarmMaxStocksPerRun = 20;
    private int prewarmMaxGenerationsPerRun = 20;
}
