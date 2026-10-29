package com.app.maria.domain.risk.service;

import com.app.maria.domain.risk.calendar.KrxTradingCalendar;
import com.app.maria.domain.risk.type.RiskDataStatus;
import com.app.maria.global.client.risk.PythonRiskClient;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.time.ZonedDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class RiskGradeSyncServiceImpl implements RiskGradeSyncService {
    private final PythonRiskClient client;
    private final RiskGradeStore store;

    public RiskGradeSyncServiceImpl(PythonRiskClient client, RiskGradeStore store) {
        this.client = client;
        this.store = store;
    }

    @Override
    public Result pull(KrxTradingCalendar.Snapshot calendar, ZonedDateTime now) {
        var reference = calendar.latest(now);
        var rows = client.fetch();
        int changed = 0, duplicates = 0, ignored = 0, rejected = 0;
        boolean retryable = false;
        for (var row : rows) {
            // A delayed response may already have expired; never resurrect it.
            if (row.priceTo().isAfter(reference)
                    || row.asOf()
                            .atZoneSameInstant(com.app.maria.domain.risk.clock.RiskMarketClock.ZONE)
                            .toLocalDate()
                            .isBefore(row.priceTo())
                    || calendar.state(row.priceTo(), reference) == RiskDataStatus.EXPIRED) {
                rejected++;
                continue;
            }
            try {
                switch (store.store(row, now)) {
                    case INSERTED, UPDATED -> changed++;
                    case DUPLICATE -> duplicates++;
                    case IGNORED -> ignored++;
                    case CONFLICT -> rejected++;
                }
            } catch (AppException e) {
                rejected++;
                retryable |= e.getErrorType() == ErrorType.RISK_SYNC_CONFLICT;
                log.warn(
                        "RISK_SYNC_ITEM_FAILED ticker={} code={}",
                        row.ticker(),
                        e.getErrorType().name());
            } catch (DataAccessException e) {
                rejected++;
                retryable = true;
                log.warn("RISK_SYNC_ITEM_FAILED ticker={} code=STORAGE_UNAVAILABLE", row.ticker());
            }
        }
        return new Result(rows.size(), changed, duplicates, ignored, rejected, retryable);
    }
}
