package com.app.maria.domain.notification.service;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.notification.dto.CustomerNotificationResponseDTO;
import com.app.maria.domain.notification.mapper.CustomerNotificationMapper;
import com.app.maria.global.clock.service.BusinessClockService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomerNotificationServiceImpl implements CustomerNotificationService {

    private static final int RECENT_LIMIT = 50;

    private final CustomerNotificationMapper notificationMapper;
    private final AccountMapper accountMapper;
    private final CustomerNotificationSettingsService settingsService;
    private final BusinessClockService businessClockService;

    @Override
    @Transactional(readOnly = true)
    public List<CustomerNotificationResponseDTO> list(Long customerId) {
        return notificationMapper.selectRecentByCustomerId(customerId, RECENT_LIMIT).stream()
                .map(CustomerNotificationResponseDTO::new)
                .toList();
    }

    @Override
    @Transactional
    public void markAllRead(Long customerId) {
        notificationMapper.markAllUnreadAsRead(customerId, businessClockService.now());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordSettlementFinalized(
            Long accountId, Long batchId, LocalDateTime completedAt) {
        AccountDTO account = accountMapper.selectByAccountId(accountId).orElse(null);
        if (account == null
                || notificationMapper.existsSettlementFinalized(account.getCustomerId(), batchId)
                || !settingsService.isSettlementPushEnabled(account.getCustomerId())) {
            return false;
        }
        try {
            return notificationMapper.insertSettlementFinalized(
                            account.getCustomerId(),
                            batchId,
                            "정산이 완료됐어요",
                            "매도 대금 정산이 완료됐습니다. 배치 번호: " + batchId,
                            completedAt)
                    > 0;
        } catch (DuplicateKeyException duplicate) {
            // 동시 완료·재실행의 동일 사건만 건너뛴다. 나머지 무결성 오류는 호출자에게 전달한다.
            return false;
        }
    }
}
