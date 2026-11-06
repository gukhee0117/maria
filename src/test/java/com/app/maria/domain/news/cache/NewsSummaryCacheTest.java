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
        var cache = new NewsSummaryCache(clock);
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
        clock.instant = clock.instant.plusSeconds(601);
        assertThat(cache.get("a", () -> "two").value().text()).isEqualTo("two");
    }
}
