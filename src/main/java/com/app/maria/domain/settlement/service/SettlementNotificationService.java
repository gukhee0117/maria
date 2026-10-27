package com.app.maria.domain.settlement.service;

import com.app.maria.domain.settlement.event.SettlementItemCompletedEvent;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface SettlementNotificationService {

    SseEmitter subscribeCustomer(Long customerId);

    void notifyAccountIfCompleted(SettlementItemCompletedEvent event);
}
