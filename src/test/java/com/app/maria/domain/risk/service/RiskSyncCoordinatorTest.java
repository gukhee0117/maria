package com.app.maria.domain.risk.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.app.maria.domain.risk.RiskTestSupport;
import com.app.maria.domain.risk.clock.RiskMarketClock;
import com.app.maria.domain.risk.dto.RiskSyncStateDTO;
import com.app.maria.domain.risk.mapper.RiskSyncMapper;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class RiskSyncCoordinatorTest {
    static class Mutable extends Clock {
        java.time.Instant instant;

        Mutable(String text) {
            instant = RiskTestSupport.at(text).toInstant();
        }

        public ZoneId getZone() {
            return RiskMarketClock.ZONE;
        }

        public Clock withZone(ZoneId z) {
            return this;
        }

        public java.time.Instant instant() {
            return instant;
        }

        void plus(long seconds) {
            instant = instant.plusSeconds(seconds);
        }
    }

    @Test
    void pollingAndRetriesUseOnePersistentDueStateAndStopAtConfiguredCutoff() throws Exception {
        var p = RiskTestSupport.properties();
        p.setMaxRetries(2);
        p.setCutoffAfterClose(Duration.ofHours(1));
        var time = new Mutable("2026-10-28T15:40:00+09:00");
        var state = new RiskSyncStateDTO();
        var mapper = mock(RiskSyncMapper.class);
        when(mapper.findState()).thenAnswer(i -> state);
        when(mapper.saveState(any())).thenReturn(1);
        RiskSyncLease lease =
                work -> {
                    work.run();
                    return true;
                };
        var sync = mock(RiskGradeSyncService.class);
        when(sync.pull(any(), any()))
                .thenThrow(new AppException(ErrorType.RISK_UPSTREAM_UNAVAILABLE))
                .thenThrow(new AppException(ErrorType.RISK_UPSTREAM_UNAVAILABLE))
                .thenReturn(new RiskGradeSyncService.Result(1, 1, 0, 0, 0, false));
        var coordinator =
                new RiskSyncCoordinator(
                        p,
                        new RiskMarketClock(time),
                        RiskTestSupport.calendar(),
                        lease,
                        mapper,
                        sync);
        coordinator.tick();
        assertThat(state.getLastRunKind()).isEqualTo("PERIODIC");
        coordinator.tick();
        verify(sync, times(1)).pull(any(), any());
        time.plus(60);
        coordinator.tick();
        assertThat(state.getLastRunKind()).isEqualTo("RETRY");
        assertThat(state.getRetryCount()).isEqualTo(1);
        time.plus(60);
        coordinator.tick();
        assertThat(state.getNextRetryAt()).isNull();
        assertThat(state.getLastErrorCode()).isNull();
        assertThat(state.getLastSuccessAt()).isNotNull();
        time.plus(60);
        coordinator.tick();
        verify(sync, times(3)).pull(any(), any());
        time.plus(47 * 60);
        coordinator.tick();
        verify(sync, times(3)).pull(any(), any());
    }

    @Test
    void closedDaysAndBeforeStartHaveNoLockOrNetworkCalls() throws Exception {
        var p = RiskTestSupport.properties();
        var lease = mock(RiskSyncLease.class);
        var sync = mock(RiskGradeSyncService.class);
        for (String text :
                java.util.List.of(
                        "2026-10-28T15:39:59+09:00",
                        "2026-10-29T16:39:59+09:00",
                        "2026-10-27T17:00:00+09:00",
                        "2026-10-31T17:00:00+09:00")) {
            var c =
                    new RiskSyncCoordinator(
                            p,
                            RiskTestSupport.clock(text),
                            RiskTestSupport.calendar(),
                            lease,
                            mock(RiskSyncMapper.class),
                            sync);
            assertThat(c.tick()).isFalse();
        }
        verifyNoInteractions(lease, sync);
    }

    @Test
    void retryExhaustionWaitsForNextPollInsteadOfSpinning() throws Exception {
        var p = RiskTestSupport.properties();
        p.setMaxRetries(1);
        var t = new Mutable("2026-10-28T15:40:00+09:00");
        var state = new RiskSyncStateDTO();
        var mapper = mock(RiskSyncMapper.class);
        when(mapper.findState()).thenReturn(state);
        when(mapper.saveState(any())).thenReturn(1);
        var sync = mock(RiskGradeSyncService.class);
        when(sync.pull(any(), any()))
                .thenThrow(new AppException(ErrorType.RISK_UPSTREAM_UNAVAILABLE));
        var c =
                new RiskSyncCoordinator(
                        p,
                        new RiskMarketClock(t),
                        RiskTestSupport.calendar(),
                        w -> {
                            w.run();
                            return true;
                        },
                        mapper,
                        sync);
        c.tick();
        t.plus(60);
        c.tick();
        assertThat(state.getNextRetryAt()).isNull();
        assertThat(state.getRetryCount()).isEqualTo(1);
        c.tick();
        verify(sync, times(2)).pull(any(), any());
        t.plus(300);
        c.tick();
        assertThat(state.getLastRunKind()).isEqualTo("PERIODIC");
    }
}
