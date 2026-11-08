package com.app.maria.domain.notification.service;

import com.app.maria.domain.notification.dto.CustomerNotificationResponseDTO;
import java.util.List;

public interface CustomerNotificationService {
    List<CustomerNotificationResponseDTO> list(Long customerId);

    void markAllRead(Long customerId);

    void markVisibleRead(Long customerId, List<Long> notificationIds);

    boolean recordSettlementFinalized(Long accountId, Long batchId);
}
