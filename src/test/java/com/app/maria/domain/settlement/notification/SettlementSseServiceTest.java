package com.app.maria.domain.settlement.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.settlement.dto.response.SettlementNotificationResponseDTO;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SettlementSseServiceTest {

    @Test
    void sendsToAllAdminsAndOnlyTheMatchingAccount() throws IOException {
        SseEmitter adminEmitter = org.mockito.Mockito.mock(SseEmitter.class);
        SseEmitter accountOneEmitter = org.mockito.Mockito.mock(SseEmitter.class);
        SseEmitter accountTwoEmitter = org.mockito.Mockito.mock(SseEmitter.class);
        @SuppressWarnings("unchecked")
        Supplier<SseEmitter> emitterFactory = org.mockito.Mockito.mock(Supplier.class);
        when(emitterFactory.get()).thenReturn(adminEmitter, accountOneEmitter, accountTwoEmitter);
        SettlementSseService service = new SettlementSseService(emitterFactory);

        service.subscribeAdmin();
        service.subscribeAccount(1L);
        service.subscribeAccount(2L);
        reset(adminEmitter, accountOneEmitter, accountTwoEmitter);

        service.sendSettlementCompleted(notification(1L));

        verify(adminEmitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(accountOneEmitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(accountTwoEmitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    private SettlementNotificationResponseDTO notification(Long accountId) {
        return new SettlementNotificationResponseDTO(
                10L,
                20L,
                accountId,
                30L,
                new BigDecimal("100000.00"),
                LocalDateTime.of(2026, 10, 27, 15, 0));
    }
}
