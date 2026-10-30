package com.app.maria.domain.risk.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties("custom.risk")
public class RiskProperties {
    private String url = "http://risk:8000";
    private String serviceKey = "";
    private String calendarPath = "";
    private String tradingHoursPath = "";
    private String expectedCalendarVersion = "";
    private boolean syncEnabled = false;
    private Duration pollInterval = Duration.ofMinutes(5);
    private Duration retryDelay = Duration.ofMinutes(1);
    private int maxRetries = 3;
    // Zero: no earlier cutoff than midnight KST. Positive: offset from session close.
    private Duration cutoffAfterClose = Duration.ZERO;

    public void validateSchedule() {
        if (pollInterval == null
                || pollInterval.isZero()
                || pollInterval.isNegative()
                || retryDelay == null
                || retryDelay.isZero()
                || retryDelay.isNegative()
                || maxRetries < 0
                || maxRetries > 20
                || cutoffAfterClose == null
                || cutoffAfterClose.isNegative()) {
            throw new IllegalArgumentException("Invalid risk sync schedule");
        }
    }
}
