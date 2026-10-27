package com.app.maria.domain.settlement.notification;

import com.app.maria.domain.settlement.dto.response.SettlementNotificationResponseDTO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface SettlementSseService {

    SseEmitter subscribeAdmin();

    SseEmitter subscribeAccount(Long accountId);

    void sendBatchCompleted(SettlementNotificationResponseDTO notification);

    void sendAccountBatchCompleted(Long accountId, SettlementNotificationResponseDTO notification);
}
