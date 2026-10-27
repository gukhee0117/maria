package com.app.maria.domain.settlement.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.settlement.dto.response.SettlementNotificationResponseDTO;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SettlementSseServiceTest {

    @Test
    void sendsBatchOnceToAdminsAndOnceToTheRequestedAccount() throws IOException {
        SseEmitter adminEmitter = org.mockito.Mockito.mock(SseEmitter.class);
        SseEmitter accountOneEmitter = org.mockito.Mockito.mock(SseEmitter.class);
        SseEmitter accountTwoEmitter = org.mockito.Mockito.mock(SseEmitter.class);
        @SuppressWarnings("unchecked")
        Supplier<SseEmitter> emitterFactory = org.mockito.Mockito.mock(Supplier.class);
        when(emitterFactory.get()).thenReturn(adminEmitter, accountOneEmitter, accountTwoEmitter);
        SettlementSseServiceImpl service = new SettlementSseServiceImpl(emitterFactory);

        service.subscribeAdmin();
        service.subscribeAccount(1L);
        service.subscribeAccount(2L);
        reset(adminEmitter, accountOneEmitter, accountTwoEmitter);

        SettlementNotificationResponseDTO notification = notification();
        service.sendBatchCompleted(notification);
        service.sendAccountBatchCompleted(1L, notification);

        verify(adminEmitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(accountOneEmitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(accountTwoEmitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void removesFailedEmitterWithoutCompletingItTwice() throws IOException {
        SseEmitter emitter = org.mockito.Mockito.mock(SseEmitter.class);
        @SuppressWarnings("unchecked")
        Supplier<SseEmitter> emitterFactory = org.mockito.Mockito.mock(Supplier.class);
        when(emitterFactory.get()).thenReturn(emitter);
        SettlementSseServiceImpl service = new SettlementSseServiceImpl(emitterFactory);
        service.subscribeAdmin();
        reset(emitter);
        org.mockito.Mockito.doThrow(new IOException("disconnected"))
                .when(emitter)
                .send(any(SseEmitter.SseEventBuilder.class));

        service.sendBatchCompleted(notification());
        service.sendBatchCompleted(notification());

        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter, never()).completeWithError(any());
    }

    private SettlementNotificationResponseDTO notification() {
        return new SettlementNotificationResponseDTO(10L, LocalDateTime.of(2026, 10, 27, 15, 0));
    }
}
