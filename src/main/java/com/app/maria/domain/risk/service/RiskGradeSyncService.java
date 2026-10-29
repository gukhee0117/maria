package com.app.maria.domain.risk.service;

import com.app.maria.domain.risk.calendar.KrxTradingCalendar;
import java.time.ZonedDateTime;

public interface RiskGradeSyncService {
    record Result(
            int received,
            int changed,
            int duplicates,
            int ignored,
            int rejected,
            boolean retryable) {}

    Result pull(KrxTradingCalendar.Snapshot calendar, ZonedDateTime now);
}
