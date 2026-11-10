package com.app.maria.domain.news.provider;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NewsSearchWindowResolverTest {
    @Test
    void missingBusinessClockFailsAtConstruction() {
        assertThatNullPointerException()
                .isThrownBy(() -> new NewsSearchWindowResolver(null, java.time.Clock.systemUTC()));
    }

    @Test
    void windowUsesPastBusinessDayAndCapsTodayOrFutureAtRealTime() {
        var now = java.time.Instant.parse("2026-11-10T03:00:00Z");
        var past = NewsSearchWindowResolver.window(java.time.LocalDate.of(2026, 11, 5), now);
        assertThat(past.from()).isEqualTo(java.time.Instant.parse("2026-10-29T15:00:00Z"));
        assertThat(past.to()).isEqualTo(java.time.Instant.parse("2026-11-05T15:00:00Z"));
        var today = NewsSearchWindowResolver.window(java.time.LocalDate.of(2026, 11, 10), now);
        var future = NewsSearchWindowResolver.window(java.time.LocalDate.of(2026, 11, 18), now);
        assertThat(today).isEqualTo(future);
        assertThat(future.to()).isEqualTo(now);
        assertThat(today.cacheKey()).isNotEqualTo(past.cacheKey());
        var midnight =
                NewsSearchWindowResolver.window(
                        java.time.LocalDate.of(2026, 11, 18),
                        java.time.Instant.parse("2026-11-10T15:00:00Z"));
        assertThat(midnight.cacheKey()).startsWith("2026-11-11");
        assertThat(midnight.cacheKey()).isNotEqualTo(today.cacheKey());
    }
}
