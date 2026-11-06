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
    private boolean liveSummaries = false;
    private String naverClientId = "";
    private String naverClientSecret = "";
    private String model = "gpt-4o-mini";
    private String provider = "demo";
    private String freeNewsApiKey = "";
    // Explicit opt-in: no account scanning, recurring collection or unlimited LLM work.
    private java.util.List<Long> warmupCustomerIds = java.util.List.of();
}
