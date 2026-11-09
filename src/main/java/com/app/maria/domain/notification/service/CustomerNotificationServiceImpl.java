package com.app.maria.domain.notification.service;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.notification.dto.CustomerNotificationResponseDTO;
import com.app.maria.domain.notification.dto.NotificationDeliveryDTO;
import com.app.maria.domain.notification.dto.SettlementEmailDetails;
import com.app.maria.domain.notification.event.CustomerNotificationCreated;
import com.app.maria.domain.notification.mapper.CustomerNotificationMapper;
import com.app.maria.domain.notification.type.NotificationConsentType;
import com.app.maria.domain.notification.type.NotificationType;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
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
    private final BusinessClockService businessClockService;
    private final ApplicationEventPublisher events;

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
    @Transactional
    public void markVisibleRead(Long customerId, List<Long> notificationIds) {
        notificationMapper.markVisibleAsRead(
                customerId, notificationIds, businessClockService.now());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordSettlementFinalized(Long accountId, Long batchId) {
        AccountDTO account = accountMapper.selectByAccountId(accountId).orElse(null);
        if (account == null
                || notificationMapper.existsSettlementFinalized(account.getCustomerId(), batchId))
            return false;
        SettlementEmailDetails details =
                notificationMapper.selectSettlementEmailDetails(accountId, batchId);
        if (details == null
                || details.getSettledAmount() == null
                || details.getCompletedAt() == null) {
            throw new AppException(
                    ErrorType.SETTLEMENT_STATE_CONFLICT, "정산 완료 알림의 금액·시각을 확인할 수 없습니다.");
        }
        return record(
                account.getCustomerId(),
                NotificationConsentType.NOTIFY_SETTLEMENT,
                NotificationType.SETTLEMENT_FINALIZED,
                "SETTLEMENT_BATCH",
                batchId,
                "정산이 완료됐어요",
                "매도 대금이 원화로 정산됐어요. 내 계좌에서 정산 내역을 확인해 주세요.",
                details.getCompletedAt(),
                details);
    }

    private boolean record(
            Long customerId,
            NotificationConsentType consentType,
            NotificationType type,
            String relatedType,
            Long relatedId,
            String title,
            String content,
            LocalDateTime createdAt,
            SettlementEmailDetails settlement) {
        NotificationDeliveryDTO target =
                notificationMapper.selectDeliveryTarget(customerId, consentType);
        if (target == null || !target.isKindEnabled()) return false;
        String email =
                target.isEmailEnabled() && target.getEmail() != null && !target.getEmail().isBlank()
                        ? target.getEmail().trim()
                        : null;
        if (!target.isPushEnabled() && email == null) return false;
        try {
            if (notificationMapper.insertEvent(
                            customerId, type, relatedType, relatedId, title, content, createdAt)
                    == 0) return false;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
        events.publishEvent(
                new CustomerNotificationCreated(
                        customerId, title, content, target.isPushEnabled(), email, settlement));
        return target.isPushEnabled();
    }
}
