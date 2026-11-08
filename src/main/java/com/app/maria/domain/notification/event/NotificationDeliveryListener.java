package com.app.maria.domain.notification.event;

import com.app.maria.domain.notification.service.CustomerNotificationStream;
import com.app.maria.domain.notification.service.NotificationEmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDeliveryListener {
    private final CustomerNotificationStream stream;
    private final NotificationEmailService email;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(CustomerNotificationCreated event) {
        if (event.settlement() == null) return; // Community reply delivery has been retired.
        if (event.pushEnabled()) stream.changed(event.customerId());
        if (event.email() != null) {
            try {
                email.deliverSettlement(event);
            } catch (RuntimeException failure) {
                log.error("[NOTIFICATION_EMAIL_QUEUE_FAILED] customerId={}", event.customerId());
            }
        }
    }
}
