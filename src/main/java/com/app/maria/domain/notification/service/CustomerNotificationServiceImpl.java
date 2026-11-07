package com.app.maria.domain.notification.service;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.notification.dto.CustomerNotificationResponseDTO;
import com.app.maria.domain.notification.mapper.CustomerNotificationMapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomerNotificationServiceImpl implements CustomerNotificationService {

    private static final int RECENT_LIMIT = 50;

    private final CustomerNotificationMapper notificationMapper;
    private final AccountMapper accountMapper;
    private final CustomerNotificationSettingsService settingsService;

    @Override
    @Transactional(readOnly = true)
    public List<CustomerNotificationResponseDTO> list(Long customerId) {
        return notificationMapper.selectRecentByCustomerId(customerId, RECENT_LIMIT);
    }

    @Override
    @Transactional
    public void markAllRead(Long customerId) {
        notificationMapper.markAllUnreadAsRead(customerId, LocalDateTime.now());
    }

    @Override
    @Transactional
    public boolean recordSettlementFinalized(
            Long accountId, Long batchId, LocalDateTime completedAt) {
        AccountDTO account = accountMapper.selectByAccountId(accountId).orElse(null);
        if (account == null
                || !Boolean.TRUE.equals(
                        settingsService.isSettlementPushEnabled(account.getCustomerId()))) {
            return false;
        }
        return notificationMapper.insertSettlementFinalized(
                        account.getCustomerId(),
                        batchId,
                        "정산이 완료됐어요",
                        "매도 대금 정산이 완료됐습니다. 배치 번호: " + batchId,
                        completedAt)
                > 0;
    }
}
