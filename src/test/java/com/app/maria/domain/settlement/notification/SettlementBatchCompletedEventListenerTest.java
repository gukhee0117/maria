package com.app.maria.domain.settlement.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.app.maria.domain.settlement.dto.response.SettlementNotificationResponseDTO;
import com.app.maria.domain.settlement.event.SettlementBatchCompletedEvent;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SettlementBatchCompletedEventListenerTest {

    @Mock private SettlementSseService settlementSseService;

    @InjectMocks private SettlementBatchCompletedEventListener listener;

    @Test
    void sendsOneAdminNotificationWhenBatchCompletes() {
        LocalDateTime completedAt = LocalDateTime.of(2026, 10, 27, 14, 30);
        SettlementBatchCompletedEvent event = new SettlementBatchCompletedEvent(1L, completedAt);

        listener.handle(event);

        ArgumentCaptor<SettlementNotificationResponseDTO> captor =
                ArgumentCaptor.forClass(SettlementNotificationResponseDTO.class);
        verify(settlementSseService).sendBatchCompleted(captor.capture());
        assertThat(captor.getValue().batchId()).isEqualTo(1L);
        assertThat(captor.getValue().completedAt()).isEqualTo(completedAt);
    }
}
