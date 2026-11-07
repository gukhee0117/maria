package com.app.maria.domain.news.cache;

import com.app.maria.domain.news.config.NewsProperties;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Validated public text only. Completed LRU entries never evict in-flight locks. */
@Component
public class NewsSummaryCache {
    public record Value(String text, Instant generatedAt) {}

    public record Result(Value value, boolean cached) {}

    public record Stats(
            long hits,
            long misses,
            long joined,
            long generations,
            long failures,
            int entries,
            int inFlight) {}

    private final Map<String, Value> completed = new LinkedHashMap<>(16, .75f, true);
    private final Map<String, CompletableFuture<Value>> inFlight = new HashMap<>();
    private final Clock clock;
    private final Duration ttl;
    private final int capacity;
    private final LongAdder hits = new LongAdder(),
            misses = new LongAdder(),
            joined = new LongAdder(),
            generations = new LongAdder(),
            failures = new LongAdder();

    public NewsSummaryCache() {
        this(new NewsProperties());
    }

    @Autowired
    public NewsSummaryCache(NewsProperties properties) {
        this(
                Clock.systemUTC(),
                properties.getSummaryCacheTtl(),
                properties.getSummaryCacheMaxEntries());
    }

    NewsSummaryCache(Clock clock) {
        this(clock, Duration.ofHours(24), 1024);
    }

    NewsSummaryCache(Clock clock, Duration ttl, int capacity) {
        if (ttl == null || ttl.isZero() || ttl.isNegative() || capacity < 1)
            throw new IllegalArgumentException("Invalid news summary cache limits");
        this.clock = clock;
        this.ttl = ttl;
        this.capacity = capacity;
    }

    public synchronized Optional<Value> peek(String key) {
        return Optional.ofNullable(find(key));
    }

    public synchronized void invalidate(String key) {
        completed.remove(key);
    }

    private Value find(String key) {
        var value = completed.get(key);
        if (value != null && !value.generatedAt().plus(ttl).isAfter(clock.instant())) {
            completed.remove(key);
            return null;
        }
        return value;
    }

    public Result get(String key, Supplier<String> loader) {
        CompletableFuture<Value> future;
        boolean owner;
        synchronized (this) {
            var ready = find(key);
            if (ready != null) {
                hits.increment();
                return new Result(ready, true);
            }
            future = inFlight.get(key);
            owner = future == null;
            if (owner) {
                if (inFlight.size() >= 128) throw new IllegalStateException("News cache busy");
                future = new CompletableFuture<>();
                inFlight.put(key, future);
                misses.increment();
            } else joined.increment();
        }
        if (!owner) {
            try {
                return new Result(future.get(25, TimeUnit.SECONDS), true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("News summary wait interrupted", e);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException failure) throw failure;
                throw new IllegalStateException("News summary unavailable", e);
            } catch (TimeoutException e) {
                throw new IllegalStateException("News summary wait timed out", e);
            }
        }
        try {
            generations.increment();
            var value = new Value(loader.get(), clock.instant());
            synchronized (this) {
                completed
                        .entrySet()
                        .removeIf(
                                e ->
                                        !e.getValue()
                                                .generatedAt()
                                                .plus(ttl)
                                                .isAfter(clock.instant()));
                completed.put(key, value);
                while (completed.size() > capacity)
                    completed.remove(completed.keySet().iterator().next());
            }
            future.complete(value);
            return new Result(value, false);
        } catch (RuntimeException | Error e) {
            failures.increment();
            future.completeExceptionally(e);
            throw e;
        } finally {
            synchronized (this) {
                inFlight.remove(key, future);
            }
        }
    }

    public synchronized Stats stats() {
        return new Stats(
                hits.sum(),
                misses.sum(),
                joined.sum(),
                generations.sum(),
                failures.sum(),
                completed.size(),
                inFlight.size());
    }
}
