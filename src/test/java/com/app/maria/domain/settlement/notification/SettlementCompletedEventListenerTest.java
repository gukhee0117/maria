package com.app.maria.domain.settlement.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.app.maria.domain.settlement.dto.response.SettlementNotificationResponseDTO;
import com.app.maria.domain.settlement.event.SettlementCompletedEvent;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SettlementCompletedEventListenerTest {

    @Mock private SettlementSseService settlementSseService;

    @InjectMocks private SettlementCompletedEventListener listener;

    @Test
    void sendsCompletedSettlementAsSseNotification() {
        LocalDateTime settledAt = LocalDateTime.of(2026, 10, 27, 14, 30);
        SettlementCompletedEvent event =
                new SettlementCompletedEvent(
                        1L, 2L, 3L, 4L, new BigDecimal("1500000.00"), settledAt);

        listener.handle(event);

        ArgumentCaptor<SettlementNotificationResponseDTO> captor =
                ArgumentCaptor.forClass(SettlementNotificationResponseDTO.class);
        verify(settlementSseService).sendSettlementCompleted(captor.capture());
        SettlementNotificationResponseDTO notification = captor.getValue();
        assertThat(notification.batchId()).isEqualTo(1L);
        assertThat(notification.itemId()).isEqualTo(2L);
        assertThat(notification.accountId()).isEqualTo(3L);
        assertThat(notification.exchangeId()).isEqualTo(4L);
        assertThat(notification.finalAmount()).isEqualByComparingTo("1500000.00");
        assertThat(notification.settledAt()).isEqualTo(settledAt);
    }
}
