package com.app.maria.domain.risk.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.app.maria.domain.risk.clock.RiskMarketClock;
import com.app.maria.global.client.risk.PythonStockClassificationClient;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class CurrentStockRiskServiceTest {
    @Test
    void slowHttpForOneTickerDoesNotBlockCachedOrUncachedOtherTickers() throws Exception {
        var client = mock(PythonStockClassificationClient.class);
        var clock = mock(RiskMarketClock.class);
        var now = ZonedDateTime.parse("2026-11-05T12:00:00+09:00");
        when(clock.now()).thenReturn(now);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(client.fetch(anyString()))
                .thenAnswer(
                        invocation -> {
                            String ticker = invocation.getArgument(0);
                            if (ticker.equals("000660")) {
                                entered.countDown();
                                if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                    throw new AssertionError("HTTP test timed out");
                            }
                            return new PythonStockClassificationClient.Classification(
                                    ticker, 2, now.toOffsetDateTime(), "STOCK_RULE_CURRENT_STATUS");
                        });
        var service = new CurrentStockRiskService(client, clock, 77, 60);
        service.findCurrent("005930");
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var slow = executor.submit(() -> service.findCurrent("000660"));
            assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(
                            executor.submit(() -> service.findCurrent("005930"))
                                    .get(2, java.util.concurrent.TimeUnit.SECONDS)
                                    .ticker())
                    .isEqualTo("005930");
            assertThat(
                            executor.submit(() -> service.findCurrent("035420"))
                                    .get(2, java.util.concurrent.TimeUnit.SECONDS)
                                    .ticker())
                    .isEqualTo("035420");
            release.countDown();
            assertThat(slow.get(2, java.util.concurrent.TimeUnit.SECONDS).ticker())
                    .isEqualTo("000660");
            verify(client, times(1)).fetch("005930");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void cacheExpiresAndNeverFallsBackOnRefreshFailure() {
        var client = mock(PythonStockClassificationClient.class);
        var clock = mock(RiskMarketClock.class);
        var now = ZonedDateTime.parse("2026-11-05T12:00:00+09:00");
        when(clock.now()).thenReturn(now, now.plusSeconds(59), now.plusSeconds(60));
        when(client.fetch("005930"))
                .thenReturn(
                        new PythonStockClassificationClient.Classification(
                                "005930", 2, now.toOffsetDateTime(), "STOCK_RULE_CURRENT_STATUS"))
                .thenThrow(new AppException(ErrorType.RISK_UPSTREAM_UNAVAILABLE));
        var service = new CurrentStockRiskService(client, clock, 77, 60);
        assertThat(service.findCurrent("005930").riskGrade()).isEqualTo(2);
        assertThat(service.findCurrent("005930").riskGrade()).isEqualTo(2);
        assertThatThrownBy(() -> service.findCurrent("005930")).isInstanceOf(AppException.class);
        verify(client, times(2)).fetch("005930");
    }

    @Test
    void configuredTtlAndCapacityAreApplied() {
        var client = mock(PythonStockClassificationClient.class);
        var clock = mock(RiskMarketClock.class);
        var now = ZonedDateTime.parse("2026-11-05T12:00:00+09:00");
        when(clock.now()).thenReturn(now, now.plusSeconds(3599), now.plusSeconds(3600));
        when(client.fetch("005930"))
                .thenReturn(
                        new PythonStockClassificationClient.Classification(
                                "005930", 2, now.toOffsetDateTime(), "STOCK_RULE_CURRENT_STATUS"))
                .thenThrow(new AppException(ErrorType.RISK_UPSTREAM_UNAVAILABLE));
        var service = new CurrentStockRiskService(client, clock, 500, 3600);
        assertThat(service.findCurrent("005930").riskGrade()).isEqualTo(2);
        assertThat(service.findCurrent("005930").riskGrade()).isEqualTo(2);
        assertThatThrownBy(() -> service.findCurrent("005930")).isInstanceOf(AppException.class);
        verify(client, times(2)).fetch("005930");
    }

    @Test
    void staleRemoteClassificationIsRejected() {
        var client = mock(PythonStockClassificationClient.class);
        var clock = mock(RiskMarketClock.class);
        var now = ZonedDateTime.parse("2026-11-05T12:00:00+09:00");
        when(clock.now()).thenReturn(now);
        when(client.fetch("005930"))
                .thenReturn(
                        new PythonStockClassificationClient.Classification(
                                "005930",
                                2,
                                now.minusMinutes(3).toOffsetDateTime(),
                                "STOCK_RULE_CURRENT_STATUS"));
        assertThatThrownBy(
                        () ->
                                new CurrentStockRiskService(client, clock, 77, 60)
                                        .findCurrent("005930"))
                .isInstanceOf(AppException.class);
    }
}
