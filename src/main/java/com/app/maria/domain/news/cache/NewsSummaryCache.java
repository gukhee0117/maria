package com.app.maria.domain.news.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Bounded, short-lived summary-only cache. In-flight identical summaries coalesce. */
@Component
public class NewsSummaryCache {
    public record Value(String text, Instant generatedAt) {}

    public record Result(Value value, boolean cached) {}

    private final ConcurrentMap<String, CompletableFuture<Value>> entries =
            new ConcurrentHashMap<>();
    private final Clock clock;

    public NewsSummaryCache() {
        this(Clock.systemUTC());
    }

    NewsSummaryCache(Clock clock) {
        this.clock = clock;
    }

    /** Never starts or waits for generation on the list response path. */
    public java.util.Optional<Value> peek(String key) {
        var future = entries.get(key);
        if (future == null
                || !future.isDone()
                || future.isCompletedExceptionally()
                || future.isCancelled()) return java.util.Optional.empty();
        var value = future.join();
        if (!value.generatedAt().plus(Duration.ofMinutes(10)).isAfter(clock.instant())) {
            entries.remove(key, future);
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(value);
    }

    public Result get(String key, Supplier<String> loader) {
        entries.entrySet()
                .removeIf(
                        e ->
                                e.getValue().isDone()
                                        && !e.getValue().isCompletedExceptionally()
                                        && e.getValue()
                                                .join()
                                                .generatedAt()
                                                .plus(Duration.ofMinutes(10))
                                                .isBefore(clock.instant()));
        CompletableFuture<Value> created = new CompletableFuture<>();
        CompletableFuture<Value> existing;
        synchronized (entries) {
            // Only completed entries may be evicted: never drop an in-flight lock.
            if (entries.size() >= 128 && !entries.containsKey(key)) {
                entries.entrySet().stream()
                        .filter(e -> e.getValue().isDone())
                        .findFirst()
                        .ifPresent(e -> entries.remove(e.getKey(), e.getValue()));
                if (entries.size() >= 128) throw new IllegalStateException("News cache busy");
            }
            existing = entries.putIfAbsent(key, created);
        }
        if (existing != null) {
            try {
                return new Result(existing.get(25, TimeUnit.SECONDS), true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (ExecutionException | TimeoutException e) {
                throw new IllegalStateException(e);
            }
        }
        try {
            Value value = new Value(loader.get(), clock.instant());
            created.complete(value);
            return new Result(value, false);
        } catch (RuntimeException e) {
            created.completeExceptionally(e);
            entries.remove(key, created);
            throw e;
        }
    }
}
