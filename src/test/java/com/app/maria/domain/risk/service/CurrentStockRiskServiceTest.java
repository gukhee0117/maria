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
        var service = new CurrentStockRiskService(client, clock);
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
        assertThatThrownBy(() -> new CurrentStockRiskService(client, clock).findCurrent("005930"))
                .isInstanceOf(AppException.class);
    }
}
