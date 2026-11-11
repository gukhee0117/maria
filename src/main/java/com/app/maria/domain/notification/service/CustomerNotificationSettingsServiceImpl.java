package com.app.maria.domain.notification.service;

import com.app.maria.domain.notification.dto.CustomerNotificationConsentDTO;
import com.app.maria.domain.notification.dto.CustomerNotificationSettingsRequestDTO;
import com.app.maria.domain.notification.dto.CustomerNotificationSettingsResponseDTO;
import com.app.maria.domain.notification.mapper.CustomerNotificationSettingsMapper;
import com.app.maria.domain.notification.type.NotificationConsentType;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomerNotificationSettingsServiceImpl
        implements CustomerNotificationSettingsService {

    private final CustomerNotificationSettingsMapper settingsMapper;
    private final BusinessClockService businessClockService;
    private final NotificationEmailVerificationService verification;

    @Override
    @Transactional(readOnly = true)
    public CustomerNotificationSettingsResponseDTO get(Long customerId) {
        Map<NotificationConsentType, Boolean> values =
                values(settingsMapper.selectLatestByCustomerId(customerId));
        if (agreed(values, NotificationConsentType.NOTIFY_EMAIL)) {
            String email = settingsMapper.selectEmail(customerId);
            if (!verification.isRegisteredEmailVerified(customerId, email)) {
                values.put(NotificationConsentType.NOTIFY_EMAIL, false);
            }
        }
        return toResponse(values);
    }

    @Override
    @Transactional
    public CustomerNotificationSettingsResponseDTO update(
            Long customerId, CustomerNotificationSettingsRequestDTO request) {
        // 잠금 읽기를 최초 조회로 사용해 같은 고객의 PUT을 직렬화한다.
        if (settingsMapper.lockCustomer(customerId) == null) {
            throw new AppException(ErrorType.CUSTOMER_NOT_FOUND, customerId);
        }
        if (Boolean.TRUE.equals(request.email())
                && !settingsMapper.hasRegisteredEmail(customerId)) {
            throw new AppException(ErrorType.CUSTOMER_NOTIFICATION_EMAIL_REQUIRED);
        }
        if (Boolean.TRUE.equals(request.email())
                && !verification.isRegisteredEmailVerified(
                        customerId, settingsMapper.selectEmail(customerId))) {
            throw new AppException(ErrorType.NOTIFICATION_EMAIL_VERIFICATION_REQUIRED);
        }
        Map<NotificationConsentType, Boolean> previous =
                values(settingsMapper.selectLatestByCustomerId(customerId));
        LocalDateTime changedAt = businessClockService.now();
        for (NotificationConsentType type : NotificationConsentType.values()) {
            if (type == NotificationConsentType.NOTIFY_COMMENT
                    || type == NotificationConsentType.NOTIFY_KIRU) continue;
            boolean next = requestedValue(request, type);
            boolean changed = agreed(previous, type) != next;
            if (type == NotificationConsentType.NOTIFY_EMAIL && !previous.containsKey(type)) {
                // The required email field is explicit in this full-settings PUT. Persist both
                // choices even when there is no previous row or ownership proof yet.
                changed = true;
            }
            if (changed) {
                settingsMapper.insertConsent(customerId, type, next, changedAt);
            }
        }
        return get(customerId);
    }

    private CustomerNotificationSettingsResponseDTO toResponse(
            Map<NotificationConsentType, Boolean> values) {
        return new CustomerNotificationSettingsResponseDTO(
                agreed(values, NotificationConsentType.NOTIFY_PUSH),
                agreed(values, NotificationConsentType.NOTIFY_SMS),
                agreed(values, NotificationConsentType.NOTIFY_EMAIL),
                agreed(values, NotificationConsentType.NOTIFY_SETTLEMENT),
                agreed(values, NotificationConsentType.NOTIFY_BAND),
                false,
                false);
    }

    private Map<NotificationConsentType, Boolean> values(
            List<CustomerNotificationConsentDTO> rows) {
        Map<NotificationConsentType, Boolean> values = new EnumMap<>(NotificationConsentType.class);
        if (rows != null) rows.forEach(row -> values.put(row.getConsentType(), row.isAgreed()));
        return values;
    }

    private boolean agreed(
            Map<NotificationConsentType, Boolean> values, NotificationConsentType type) {
        return values.getOrDefault(type, type.defaultValue());
    }

    private boolean requestedValue(
            CustomerNotificationSettingsRequestDTO request, NotificationConsentType type) {
        return switch (type) {
            case NOTIFY_PUSH -> request.push();
            case NOTIFY_SMS -> request.sms();
            case NOTIFY_EMAIL -> request.email();
            case NOTIFY_SETTLEMENT -> request.settle();
            case NOTIFY_BAND -> request.band();
            case NOTIFY_COMMENT -> false;
            case NOTIFY_KIRU -> false;
        };
    }
}
