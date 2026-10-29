package com.app.maria.domain.risk.clock;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Market time is independent of the mutable business SYSTEM_CLOCK. */
public final class RiskMarketClock {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private final Clock clock;

    public RiskMarketClock(Clock clock) {
        this.clock = clock;
    }

    public ZonedDateTime now() {
        return clock.instant().atZone(ZONE);
    }
}
