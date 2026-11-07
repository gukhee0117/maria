package com.app.maria.domain.notification.service;

import com.app.maria.domain.notification.dto.CustomerNotificationConsentDTO;
import com.app.maria.domain.notification.dto.CustomerNotificationSettingsRequestDTO;
import com.app.maria.domain.notification.dto.CustomerNotificationSettingsResponseDTO;
import com.app.maria.domain.notification.mapper.CustomerNotificationSettingsMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomerNotificationSettingsServiceImpl
        implements CustomerNotificationSettingsService {

    private static final Map<String, String> CONSENT_TYPES =
            Map.of(
                    "push", "NOTIFY_PUSH",
                    "sms", "NOTIFY_SMS",
                    "email", "NOTIFY_EMAIL",
                    "settle", "NOTIFY_SETTLEMENT",
                    "band", "NOTIFY_BAND",
                    "reply", "NOTIFY_COMMENT",
                    "kiru", "NOTIFY_KIRU");

    private final CustomerNotificationSettingsMapper settingsMapper;

    @Override
    @Transactional(readOnly = true)
    public CustomerNotificationSettingsResponseDTO get(Long customerId) {
        return toResponse(settingsMapper.selectLatestByCustomerId(customerId));
    }

    @Override
    @Transactional
    public CustomerNotificationSettingsResponseDTO update(
            Long customerId, CustomerNotificationSettingsRequestDTO request) {
        Map<String, Boolean> values = values(request);
        CONSENT_TYPES.forEach(
                (key, consentType) ->
                        settingsMapper.insertConsent(customerId, consentType, values.get(key)));
        return get(customerId);
    }

    @Override
    @Transactional(readOnly = true)
    public Boolean isSettlementPushEnabled(Long customerId) {
        CustomerNotificationSettingsResponseDTO settings = get(customerId);
        return settings.push() && settings.settle();
    }

    private CustomerNotificationSettingsResponseDTO toResponse(
            List<CustomerNotificationConsentDTO> rows) {
        Map<String, Boolean> values = new HashMap<>();
        if (rows != null) {
            rows.forEach(row -> values.put(row.getConsentType(), row.isAgreed()));
        }
        return new CustomerNotificationSettingsResponseDTO(
                values.getOrDefault("NOTIFY_PUSH", true),
                values.getOrDefault("NOTIFY_SMS", false),
                values.getOrDefault("NOTIFY_EMAIL", false),
                values.getOrDefault("NOTIFY_SETTLEMENT", true),
                values.getOrDefault("NOTIFY_BAND", true),
                values.getOrDefault("NOTIFY_COMMENT", true),
                values.getOrDefault("NOTIFY_KIRU", true));
    }

    private Map<String, Boolean> values(CustomerNotificationSettingsRequestDTO request) {
        return Map.of(
                "push", request.push(),
                "sms", request.sms(),
                "email", request.email(),
                "settle", request.settle(),
                "band", request.band(),
                "reply", request.reply(),
                "kiru", request.kiru());
    }
}
