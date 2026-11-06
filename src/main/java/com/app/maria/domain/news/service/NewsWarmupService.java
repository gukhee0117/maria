package com.app.maria.domain.news.service;

import com.app.maria.domain.news.config.NewsProperties;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Optional, bounded, startup-only warmup. Uses the exact same authorization/selection path. */
@Component
@RequiredArgsConstructor
@Slf4j
public class NewsWarmupService {
    private final NewsBriefingService service;
    private final NewsProperties properties;
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    task -> {
                        var thread = new Thread(task, "news-first-summary-warmup");
                        thread.setDaemon(true);
                        return thread;
                    });

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        for (var customerId :
                properties.getWarmupCustomerIds().stream()
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .limit(2)
                        .toList()) {
            worker.execute(
                    () -> {
                        try {
                            var briefing = service.briefing(customerId);
                            if (!briefing.articles().isEmpty() && briefing.firstSummary() == null) {
                                var article = briefing.articles().get(0).article();
                                service.summary(customerId, article.id(), article.version());
                            }
                            log.info("News first-summary warmup completed");
                        } catch (RuntimeException e) {
                            // Normal requests remain available and retry through the usual API
                            // path.
                            log.warn(
                                    "News first-summary warmup unavailable (type={})",
                                    e.getClass().getSimpleName());
                        }
                    });
        }
    }

    @PreDestroy
    public void close() {
        worker.shutdownNow();
    }
}
