package com.app.maria.domain.settlement.notification;

import com.app.maria.domain.settlement.dto.response.SettlementNotificationResponseDTO;
import com.app.maria.domain.settlement.event.SettlementCompletedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class SettlementCompletedEventListener {

    private final SettlementSseService settlementSseService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(SettlementCompletedEvent event) {
        settlementSseService.sendSettlementCompleted(SettlementNotificationResponseDTO.from(event));
    }
}
