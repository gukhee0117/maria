package com.app.maria.domain.settlement.notification;

import com.app.maria.domain.settlement.event.SettlementItemCompletedEvent;
import com.app.maria.domain.settlement.service.SettlementNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class SettlementItemCompletedEventListener {

    private final SettlementNotificationService settlementNotificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(SettlementItemCompletedEvent event) {
        settlementNotificationService.notifyAccountIfCompleted(event);
    }
}
