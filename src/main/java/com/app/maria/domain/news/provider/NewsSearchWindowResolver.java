package com.app.maria.domain.news.provider;

import com.app.maria.domain.news.dto.NewsSearchWindow;
import com.app.maria.global.clock.service.BusinessClockService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/** Provider-internal business-date policy. No public date calculation entry point. */
final class NewsSearchWindowResolver {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final BusinessClockService businessClock;
    private final Clock clock;

    NewsSearchWindowResolver(BusinessClockService businessClock, Clock clock) {
        this.businessClock = Objects.requireNonNull(businessClock);
        this.clock = Objects.requireNonNull(clock);
    }

    NewsSearchWindow capture() {
        return window(businessClock.now().toLocalDate(), clock.instant());
    }

    static NewsSearchWindow window(LocalDate businessDate, Instant now) {
        LocalDate today = now.atZone(SEOUL).toLocalDate();
        LocalDate effective = businessDate.isBefore(today) ? businessDate : today;
        Instant end =
                effective.isBefore(today)
                        ? effective.plusDays(1).atStartOfDay(SEOUL).toInstant()
                        : now;
        Instant start = effective.minusDays(6).atStartOfDay(SEOUL).toInstant();
        // live/closed partition the date only. Both retain TTL and API request budget.
        return new NewsSearchWindow(
                start, end, effective + (effective.isBefore(today) ? ":closed" : ":live"));
    }
}
