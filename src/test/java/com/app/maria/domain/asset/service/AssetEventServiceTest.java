package com.app.maria.domain.asset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.app.maria.domain.asset.dto.response.CustomerAssetBriefingResponseDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AssetEventServiceTest {
    @Test
    void connectionTimeoutAndReconnectDelayAreConfiguredIndependently() throws Exception {
        var briefing = mock(AssetBriefingService.class);
        var mapper = new ObjectMapper();
        var actual = new AssetEventService(briefing, mapper);
        ReflectionTestUtils.setField(actual, "timeoutMs", 90000L);
        assertThat(actual.subscribe(7L).getTimeout()).isEqualTo(90000L);
        actual.stopPolling();

        var emitter = mock(SseEmitter.class);
        var service = new AssetEventService(briefing, mapper, () -> emitter);
        ReflectionTestUtils.setField(service, "pollIntervalMs", 10000L);
        ReflectionTestUtils.setField(service, "reconnectIntervalMs", 3500L);
        service.subscribe(7L);
        var event = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter).send(event.capture());
        assertThat(event.getValue().build().stream().map(v -> v.getData().toString()))
                .anyMatch(value -> value.contains("retry:3500"));
    }

    private CustomerAssetBriefingResponseDTO data(String amount, int hour) {
        return CustomerAssetBriefingResponseDTO.builder()
                .accountId(3L)
                .cashAmount(new BigDecimal(amount))
                .calculatedAt(LocalDateTime.of(2026, 11, 18, hour, 0))
                .build();
    }

    private long changed(SseEmitter emitter) {
        return mockingDetails(emitter).getInvocations().stream()
                .filter(
                        i ->
                                i.getMethod().getName().equals("send")
                                        && i.getArgument(0) instanceof SseEmitter.SseEventBuilder)
                .map(i -> (SseEmitter.SseEventBuilder) i.getArgument(0))
                .filter(
                        b ->
                                b.build().stream()
                                        .anyMatch(
                                                v ->
                                                        v.getData()
                                                                .toString()
                                                                .contains("event:asset-changed")))
                .count();
    }

    @Test
    void onlyConnectedCustomersAreReadAndClockChangesDoNotTriggerRefresh() {
        var briefing = mock(AssetBriefingService.class);
        var emitter = mock(SseEmitter.class);
        var service =
                new AssetEventService(
                        briefing, new ObjectMapper().findAndRegisterModules(), () -> emitter);
        service.poll();
        verifyNoInteractions(briefing);
        when(briefing.getBriefing(7L)).thenReturn(data("100", 9), data("100", 10), data("200", 10));
        service.subscribe(7L);
        service.poll();
        service.poll();
        assertThat(changed(emitter)).isEqualTo(1);
        service.poll();
        assertThat(changed(emitter)).isEqualTo(2);
        var completed = ArgumentCaptor.forClass(Runnable.class);
        verify(emitter).onCompletion(completed.capture());
        completed.getValue().run();
        service.poll();
        verify(briefing, times(3)).getBriefing(7L);
    }

    @Test
    void separateCustomersDoNotReceiveEachOthersChanges() {
        var briefing = mock(AssetBriefingService.class);
        var a = mock(SseEmitter.class);
        var b = mock(SseEmitter.class);
        var emitters = List.of(a, b).iterator();
        var service =
                new AssetEventService(
                        briefing, new ObjectMapper().findAndRegisterModules(), emitters::next);
        when(briefing.getBriefing(1L)).thenReturn(data("100", 9), data("200", 9));
        when(briefing.getBriefing(2L)).thenReturn(data("300", 9));
        service.subscribe(1L);
        service.subscribe(2L);
        service.poll();
        service.poll();
        assertThat(changed(a)).isEqualTo(2);
        assertThat(changed(b)).isEqualTo(1);
    }

    @Test
    void disconnectedEmitterIsRemovedWithoutKeepingBackgroundQueries() throws Exception {
        var briefing = mock(AssetBriefingService.class);
        var emitter = mock(SseEmitter.class);
        doThrow(new IOException("disconnected"))
                .when(emitter)
                .send(any(SseEmitter.SseEventBuilder.class));
        var service = new AssetEventService(briefing, new ObjectMapper(), () -> emitter);
        service.subscribe(7L);
        service.poll();
        verifyNoInteractions(briefing);
        verify(emitter).complete();
    }
}
