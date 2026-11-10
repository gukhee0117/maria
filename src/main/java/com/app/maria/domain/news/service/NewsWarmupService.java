package com.app.maria.domain.news.service;

import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.news.config.NewsProperties;
import com.app.maria.domain.news.dto.*;
import com.app.maria.domain.news.dto.NewsContract.*;
import com.app.maria.domain.news.provider.NewsProvider;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * All observed holding groups, not named accounts. Planning uses only existing source snapshots.
 */
@Component
@Slf4j
public class NewsWarmupService {
    public record Stats(
            int stocks,
            int groups,
            int targets,
            int generated,
            int reused,
            int failed,
            int deferred) {}

    private record Target(NewsArticle article, InvestorType type) {}

    private final NewsInventoryService inventory;
    private final NewsProvider provider;
    private final NewsSelectionService selection;
    private final NewsBriefingService summaries;
    private final NewsProperties properties;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Map<String, Target> pending = new LinkedHashMap<>();
    private final Map<String, Instant> retryAfter = new HashMap<>();
    private int stockCursor;
    private volatile Stats last = new Stats(0, 0, 0, 0, 0, 0, 0);
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    task -> {
                        var thread = new Thread(task, "news-public-summary-prewarm");
                        thread.setDaemon(true);
                        return thread;
                    });

    public NewsWarmupService(
            NewsInventoryService inventory,
            NewsProvider provider,
            NewsSelectionService selection,
            NewsBriefingService summaries,
            NewsProperties properties) {
        this.inventory = inventory;
        this.provider = provider;
        this.selection = selection;
        this.summaries = summaries;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        try {
            var snapshot = inventory.load();
            provider.configureInventory(context(snapshot.stockNames(), InvestorType.NEUTRAL));
        } catch (RuntimeException e) {
            log.warn("News inventory sizing unavailable (type={})", e.getClass().getSimpleName());
        }
        schedule();
    }

    @Scheduled(fixedDelayString = "${news.prewarm-interval-ms:300000}")
    public void schedule() {
        if (!properties.isPrewarmEnabled() || !running.compareAndSet(false, true)) return;
        try {
            worker.execute(
                    () -> {
                        try {
                            var stats = runCycle();
                            log.info(
                                    "News prewarm stocks={} groups={} targets={} generated={} reused={} failed={} deferred={}",
                                    stats.stocks(),
                                    stats.groups(),
                                    stats.targets(),
                                    stats.generated(),
                                    stats.reused(),
                                    stats.failed(),
                                    stats.deferred());
                        } catch (RuntimeException e) {
                            log.warn(
                                    "News prewarm unavailable (type={})",
                                    e.getClass().getSimpleName());
                        } finally {
                            running.set(false);
                        }
                    });
        } catch (RejectedExecutionException e) {
            running.set(false);
        }
    }

    public synchronized Stats runCycle() {
        if (!properties.isPrewarmEnabled()) return last;
        var snapshot = inventory.load();
        var full = context(snapshot.stockNames(), InvestorType.NEUTRAL);
        provider.configureInventory(full);
        int failures = 0, generated = 0, reused = 0, attempts = 0;
        var stocks = snapshot.stockNames().keySet().stream().sorted().toList();
        for (int i = 0;
                i < Math.min(stocks.size(), Math.max(0, properties.getPrewarmMaxStocksPerRun()));
                i++) {
            var ticker = stocks.get(Math.floorMod(stockCursor++, stocks.size()));
            var c =
                    context(
                            Map.of(ticker, snapshot.stockNames().get(ticker)),
                            InvestorType.NEUTRAL);
            var key = "source:" + ticker;
            if (retryAfter.getOrDefault(key, Instant.MIN).isAfter(Instant.now())) continue;
            try {
                var query = provider.snapshot(c);
                if (query.cachedArticles().get().isEmpty()) query.articles().get();
                retryAfter.remove(key);
            } catch (RuntimeException e) {
                failures++;
                retryAfter.put(key, Instant.now().plusSeconds(900));
            }
        }
        var targets = new LinkedHashMap<String, Target>();
        for (var group : snapshot.groups())
            for (var type : InvestorType.values()) {
                if (type == InvestorType.UNASSIGNED) continue;
                var c = context(group.stockNames(), type);
                var cached = provider.cachedArticles(c);
                if (cached.isEmpty()) continue;
                var selected = selection.select(c, cached.get());
                if (!selected.isEmpty()) {
                    var a = selected.get(0).article();
                    targets.putIfAbsent(summaries.sharedSummaryKey(a, type), new Target(a, type));
                }
            }
        pending.keySet().retainAll(targets.keySet());
        targets.forEach(pending::putIfAbsent);
        var validRetries = new HashSet<>(targets.keySet());
        stocks.forEach(t -> validRetries.add("source:" + t));
        retryAfter.keySet().retainAll(validRetries);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        for (var key : new ArrayList<>(pending.keySet())) {
            if (Thread.currentThread().isInterrupted()) break;
            var target = pending.get(key);
            if (summaries.summaryReady(target.article(), target.type())) {
                reused++;
                pending.remove(key);
                retryAfter.remove(key);
                continue;
            }
            if (attempts >= Math.max(0, properties.getPrewarmMaxGenerationsPerRun())
                    || System.nanoTime() >= deadline
                    || !summaries.backgroundCapacity()) continue;
            if (retryAfter.getOrDefault(key, Instant.MIN).isAfter(Instant.now())) continue;
            attempts++;
            try {
                var result = summaries.prepareSummary(target.article(), target.type());
                if (result.cached()) reused++;
                else generated++;
                pending.remove(key);
                retryAfter.remove(key);
            } catch (RuntimeException e) {
                failures++;
                retryAfter.put(key, Instant.now().plusSeconds(900));
                var deferred = pending.remove(key);
                pending.put(key, deferred);
            }
        }
        last =
                new Stats(
                        stocks.size(),
                        snapshot.groups().size(),
                        targets.size(),
                        generated,
                        reused,
                        failures,
                        pending.size());
        return last;
    }

    private Context context(Map<String, String> names, InvestorType type) {
        return new Context(0L, names.keySet(), type, PersonalizationStatus.ACTIVE, names);
    }

    public Stats stats() {
        return last;
    }

    @PreDestroy
    public void close() {
        worker.shutdownNow();
    }
}
