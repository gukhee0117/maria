package com.app.maria.domain.risk.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.app.maria.domain.risk.RiskTestSupport;
import com.app.maria.global.client.risk.PythonRiskClient;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

class RiskGradeSyncServiceTest {
    @Test
    void partialPublicationIsAcceptedAndNextPullCanBringMoreStocks() throws Exception {
        var client = mock(PythonRiskClient.class);
        var store = mock(RiskGradeStore.class);
        var a = RiskTestSupport.grade("005930", "2026-10-28", 1);
        var b = RiskTestSupport.grade("000660", "2026-10-28", 1);
        when(client.fetch()).thenReturn(List.of(a), List.of(a, b));
        when(store.store(eq(a), any()))
                .thenReturn(RiskGradeStore.Outcome.INSERTED, RiskGradeStore.Outcome.DUPLICATE);
        when(store.store(eq(b), any())).thenReturn(RiskGradeStore.Outcome.INSERTED);
        var service = new RiskGradeSyncServiceImpl(client, store);
        var now = RiskTestSupport.at("2026-10-28T17:00:00+09:00");
        assertThat(service.pull(RiskTestSupport.calendar().load(), now).received()).isEqualTo(1);
        var next = service.pull(RiskTestSupport.calendar().load(), now);
        assertThat(next.changed()).isEqualTo(1);
        assertThat(next.duplicates()).isEqualTo(1);
    }

    @Test
    void lockFailureIsRetryableButContentConflictIsNot() throws Exception {
        var client = mock(PythonRiskClient.class);
        var store = mock(RiskGradeStore.class);
        var a = RiskTestSupport.grade("005930", "2026-10-28", 1);
        when(client.fetch()).thenReturn(List.of(a));
        when(store.store(eq(a), any())).thenThrow(new CannotAcquireLockException("test deadlock"));
        var service = new RiskGradeSyncServiceImpl(client, store);
        var now = RiskTestSupport.at("2026-10-28T17:00:00+09:00");
        assertThat(service.pull(RiskTestSupport.calendar().load(), now).retryable()).isTrue();
        reset(store);
        when(store.store(eq(a), any())).thenReturn(RiskGradeStore.Outcome.CONFLICT);
        assertThat(service.pull(RiskTestSupport.calendar().load(), now).retryable()).isFalse();
    }

    @Test
    void failedHttpPullDoesNotTouchStoredData() throws Exception {
        var client = mock(PythonRiskClient.class);
        var store = mock(RiskGradeStore.class);
        when(client.fetch()).thenThrow(new AppException(ErrorType.RISK_UPSTREAM_UNAVAILABLE));
        var service = new RiskGradeSyncServiceImpl(client, store);
        var cal = RiskTestSupport.calendar().load();
        var now = RiskTestSupport.at("2026-10-28T17:00:00+09:00");
        assertThatThrownBy(() -> service.pull(cal, now)).isInstanceOf(AppException.class);
        verifyNoInteractions(store);
    }
}
