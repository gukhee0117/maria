package com.app.maria.domain.risk.service;

import com.app.maria.domain.risk.calendar.KrxTradingCalendar;
import com.app.maria.domain.risk.clock.RiskMarketClock;
import com.app.maria.domain.risk.config.RiskProperties;
import com.app.maria.domain.risk.mapper.RiskSyncMapper;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.time.ZonedDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class RiskSyncCoordinator {
    private final RiskProperties properties;
    private final RiskMarketClock clock;
    private final KrxTradingCalendar calendar;
    private final RiskSyncLease lease;
    private final RiskSyncMapper mapper;
    private final RiskGradeSyncService sync;

    public RiskSyncCoordinator(
            RiskProperties properties,
            RiskMarketClock clock,
            KrxTradingCalendar calendar,
            RiskSyncLease lease,
            RiskSyncMapper mapper,
            RiskGradeSyncService sync) {
        this.properties = properties;
        this.clock = clock;
        this.calendar = calendar;
        this.lease = lease;
        this.mapper = mapper;
        this.sync = sync;
    }

    public boolean tick() {
        properties.validateSchedule();
        var now = clock.now();
        var snapshot = calendar.load();
        var close = snapshot.closeToday(now);
        if (close == null || now.isBefore(close.plusMinutes(10))) return false;
        var midnight = now.toLocalDate().plusDays(1).atStartOfDay(RiskMarketClock.ZONE);
        var cutoff =
                properties.getCutoffAfterClose().isZero()
                        ? midnight
                        : close.plus(properties.getCutoffAfterClose());
        if (cutoff.isAfter(midnight)) cutoff = midnight;
        if (!now.isBefore(cutoff)) return false;
        final var deadline = cutoff;
        return lease.runIfAcquired(() -> runDue(snapshot, now, deadline));
    }

    private void runDue(
            KrxTradingCalendar.Snapshot snapshot, ZonedDateTime now, ZonedDateTime cutoff) {
        var state = mapper.findState();
        if (state == null) throw new AppException(ErrorType.RISK_SYNC_CONFLICT);
        var local = now.toLocalDateTime().withNano(0);
        if (state.getLastAttemptAt() != null
                && !state.getLastAttemptAt().toLocalDate().equals(local.toLocalDate())) {
            state.setNextRetryAt(null);
            state.setRetryCount(0);
        }
        boolean retry = state.getNextRetryAt() != null;
        if (retry
                ? local.isBefore(state.getNextRetryAt())
                : state.getNextPollAt() != null && local.isBefore(state.getNextPollAt())) return;
        if (retry) state.setRetryCount(state.getRetryCount() + 1);
        else {
            state.setRetryCount(0);
            state.setNextPollAt(local.plus(properties.getPollInterval()));
        }
        state.setLastAttemptAt(local);
        state.setLastRunKind(retry ? "RETRY" : "PERIODIC");
        boolean retryable = false;
        try {
            var result = sync.pull(snapshot, now);
            retryable = result.retryable();
            state.setLastErrorCode(result.rejected() > 0 ? "ITEM_REJECTED" : null);
            if (result.rejected() == 0) state.setLastSuccessAt(local);
            log.info(
                    "RISK_SYNC kind={} received={} changed={} duplicate={} rejected={}",
                    state.getLastRunKind(),
                    result.received(),
                    result.changed(),
                    result.duplicates(),
                    result.rejected());
        } catch (AppException e) {
            state.setLastErrorCode(e.getErrorType().name());
            retryable =
                    e.getErrorType() == ErrorType.RISK_UPSTREAM_UNAVAILABLE
                            || e.getErrorType() == ErrorType.RISK_SYNC_CONFLICT;
        } catch (org.springframework.dao.DataAccessException e) {
            state.setLastErrorCode("STORAGE_UNAVAILABLE");
            retryable = true;
        }
        state.setNextRetryAt(null);
        var nextRetry = now.plus(properties.getRetryDelay());
        if (retryable
                && state.getRetryCount() < properties.getMaxRetries()
                && nextRetry.isBefore(cutoff)) {
            state.setNextRetryAt(nextRetry.toLocalDateTime().withNano(0));
        } else if (retryable) {
            // Exhaustion never immediately falls through into another periodic attempt.
            state.setNextPollAt(local.plus(properties.getPollInterval()));
        }
        if (mapper.saveState(state) != 1) throw new AppException(ErrorType.RISK_SYNC_CONFLICT);
    }
}
