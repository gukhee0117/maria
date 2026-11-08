package com.app.maria.domain.notification.service;

import static org.mockito.Mockito.*;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class CustomerNotificationStreamTest {
    @Test
    void customerIsolationMultipleTabsAndCompletionCleanup() throws Exception {
        SseEmitter one = mock(SseEmitter.class),
                secondTab = mock(SseEmitter.class),
                other = mock(SseEmitter.class);
        java.util.ArrayDeque<SseEmitter> emitters =
                new java.util.ArrayDeque<>(java.util.List.of(one, secondTab, other));
        var stream = new CustomerNotificationStream(emitters::remove);
        stream.subscribe(1L);
        stream.subscribe(1L);
        stream.subscribe(2L);
        stream.changed(1L);
        verify(one, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(secondTab, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(other, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        ArgumentCaptor<Runnable> completion = ArgumentCaptor.forClass(Runnable.class);
        verify(one).onCompletion(completion.capture());
        completion.getValue().run();
        stream.changed(1L);
        verify(one, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(secondTab, times(3)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void brokenTabIsRemovedWithoutBreakingOtherTabs() throws Exception {
        SseEmitter broken = mock(SseEmitter.class), healthy = mock(SseEmitter.class);
        var emitters = new java.util.ArrayDeque<>(java.util.List.of(broken, healthy));
        var stream = new CustomerNotificationStream(emitters::remove);
        stream.subscribe(1L);
        stream.subscribe(1L);
        doThrow(new IOException("closed")).when(broken).send(any(SseEmitter.SseEventBuilder.class));
        stream.changed(1L);
        stream.changed(1L);
        verify(broken, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(healthy, times(3)).send(any(SseEmitter.SseEventBuilder.class));
    }
}
