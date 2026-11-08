package com.app.maria.domain.notification.service;

import com.app.maria.domain.notification.dto.CustomerNotificationResponseDTO;
import java.time.LocalDateTime;
import java.util.List;

public interface CustomerNotificationService {
    List<CustomerNotificationResponseDTO> list(Long customerId);

    void markAllRead(Long customerId);

    boolean recordSettlementFinalized(Long accountId, Long batchId, LocalDateTime completedAt);

    void recordReply(Long recipientId, Long authorId, Long commentId, boolean kiru);
}
