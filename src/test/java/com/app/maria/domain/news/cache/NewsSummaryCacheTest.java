package com.app.maria.domain.news.cache;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NewsSummaryCacheTest {
    @Test
    void peekDoesNotGenerateOrWaitForInflightAndExpiresAtBoundary() {
        var instant = Instant.parse("2026-11-06T00:00:00Z");
        var clock = org.mockito.Mockito.mock(Clock.class);
        org.mockito.Mockito.when(clock.instant()).thenReturn(instant);
        var cache = new NewsSummaryCache(clock, Duration.ofMinutes(10), 8);
        assertThat(cache.peek("missing")).isEmpty();
        cache.get(
                "key",
                () -> {
                    assertThat(cache.peek("key")).isEmpty();
                    return "text";
                });
        assertThat(cache.peek("key")).isPresent();
        org.mockito.Mockito.when(clock.instant()).thenReturn(instant.plusSeconds(600));
        assertThat(cache.peek("key")).isEmpty();
    }

    @Test
    void identicalConcurrentRequestsCoalesceAndFailuresAreNotCached() throws Exception {
        var cache = new NewsSummaryCache();
        var executor = Executors.newFixedThreadPool(2);
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var count = new AtomicInteger();
        try {
            var first =
                    executor.submit(
                            () ->
                                    cache.get(
                                            "same",
                                            () -> {
                                                count.incrementAndGet();
                                                entered.countDown();
                                                try {
                                                    if (!finish.await(3, TimeUnit.SECONDS))
                                                        throw new IllegalStateException();
                                                } catch (InterruptedException e) {
                                                    throw new IllegalStateException(e);
                                                }
                                                return "ok";
                                            }));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            var second =
                    executor.submit(
                            () ->
                                    cache.get(
                                            "same",
                                            () -> {
                                                count.incrementAndGet();
                                                return "wrong";
                                            }));
            finish.countDown();
            assertThat(first.get().cached()).isFalse();
            assertThat(second.get().cached()).isTrue();
            assertThat(count.get()).isEqualTo(1);
            assertThatThrownBy(
                            () ->
                                    cache.get(
                                            "failed",
                                            () -> {
                                                throw new IllegalStateException();
                                            }))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(cache.get("failed", () -> "recovered").cached()).isFalse();
        } finally {
            finish.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void ttlExpiresWithoutChangingExistingGenerationTime() {
        class MutableClock extends Clock {
            Instant instant = Instant.parse("2026-11-05T00:00:00Z");

            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            public Clock withZone(ZoneId z) {
                return this;
            }

            public Instant instant() {
                return instant;
            }
        }
        var clock = new MutableClock();
        var cache = new NewsSummaryCache(clock);
        var first = cache.get("a", () -> "one");
        assertThat(cache.get("a", () -> "two").value()).isEqualTo(first.value());
        clock.instant = clock.instant.plusSeconds(1200);
        assertThat(cache.get("a", () -> "unexpected").value()).isEqualTo(first.value());
        clock.instant = clock.instant.plusSeconds(86400);
        assertThat(cache.get("a", () -> "two").value().text()).isEqualTo("two");
    }

    @Test
    void thirtyConcurrentRequestsGenerateOnceAndExposeHitStatistics() throws Exception {
        var cache = new NewsSummaryCache();
        var pool = Executors.newFixedThreadPool(30);
        var finish = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var futures = new java.util.ArrayList<Future<NewsSummaryCache.Result>>();
        try {
            for (int i = 0; i < 30; i++)
                futures.add(
                        pool.submit(
                                () ->
                                        cache.get(
                                                "one-public-key",
                                                () -> {
                                                    calls.incrementAndGet();
                                                    entered.countDown();
                                                    try {
                                                        if (!finish.await(4, TimeUnit.SECONDS))
                                                            throw new IllegalStateException();
                                                    } catch (InterruptedException e) {
                                                        Thread.currentThread().interrupt();
                                                        throw new IllegalStateException(e);
                                                    }
                                                    return "validated summary";
                                                })));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (cache.stats().joined() < 29 && System.nanoTime() < until) Thread.yield();
            finish.countDown();
            for (var future : futures)
                assertThat(future.get(3, TimeUnit.SECONDS).value().text())
                        .isEqualTo("validated summary");
            assertThat(calls.get()).isEqualTo(1);
            assertThat(cache.stats().generations()).isEqualTo(1);
            assertThat(cache.stats().hits() + cache.stats().joined()).isEqualTo(29);
        } finally {
            finish.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void completedCapacityDoesNotEvictInFlightOwnership() {
        var cache = new NewsSummaryCache(Clock.systemUTC(), Duration.ofHours(24), 1);
        cache.get("a", () -> "first");
        cache.get("b", () -> "second");
        assertThat(cache.peek("a")).isEmpty();
        assertThat(cache.peek("b")).isPresent();
        assertThat(cache.stats().entries()).isEqualTo(1);
    }
}
