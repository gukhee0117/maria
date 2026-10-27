package com.app.maria.domain.settlement.notification;

import static org.mockito.Mockito.verify;

import com.app.maria.domain.settlement.event.SettlementItemCompletedEvent;
import com.app.maria.domain.settlement.service.SettlementNotificationService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SettlementItemCompletedEventListenerTest {

    @Mock private SettlementNotificationService settlementNotificationService;

    @InjectMocks private SettlementItemCompletedEventListener listener;

    @Test
    void checksWhetherTheCustomersAccountBatchHasCompleted() {
        SettlementItemCompletedEvent event =
                new SettlementItemCompletedEvent(1L, 7L, LocalDateTime.of(2026, 10, 27, 17, 30));

        listener.handle(event);

        verify(settlementNotificationService).notifyAccountIfCompleted(event);
    }
}
