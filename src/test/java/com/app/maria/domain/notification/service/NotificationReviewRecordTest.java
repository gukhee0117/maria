package com.app.maria.domain.notification.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.notification.dto.NotificationDeliveryDTO;
import com.app.maria.domain.notification.dto.SettlementEmailDetails;
import com.app.maria.domain.notification.mapper.CustomerNotificationMapper;
import com.app.maria.domain.notification.type.NotificationConsentType;
import com.app.maria.domain.notification.type.NotificationType;
import com.app.maria.global.clock.service.BusinessClockService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class NotificationReviewRecordTest {
    @Mock CustomerNotificationMapper mapper;
    @Mock AccountMapper accounts;
    @Mock ApplicationEventPublisher events;
    @Mock BusinessClockService clock;
    @InjectMocks CustomerNotificationServiceImpl service;
    private final LocalDateTime now = LocalDateTime.of(2026, 11, 8, 10, 0);

    @BeforeEach
    void setup() {
        when(accounts.selectByAccountId(7L))
                .thenReturn(Optional.of(AccountDTO.builder().customerId(1L).build()));
    }

    private void ready() {
        when(mapper.selectSettlementEmailDetails(7L, 10L))
                .thenReturn(new SettlementEmailDetails(BigDecimal.ONE, now));
        NotificationDeliveryDTO target = new NotificationDeliveryDTO();
        target.setPushEnabled(true);
        target.setKindEnabled(true);
        when(mapper.selectDeliveryTarget(1L, NotificationConsentType.NOTIFY_SETTLEMENT))
                .thenReturn(target);
    }

    @Test
    void concurrentDuplicateIsTheOnlySuppressedInsertError() {
        ready();
        when(mapper.insertEvent(
                        eq(1L),
                        eq(NotificationType.SETTLEMENT_FINALIZED),
                        eq("SETTLEMENT_BATCH"),
                        eq(10L),
                        anyString(),
                        anyString(),
                        eq(now)))
                .thenThrow(new DuplicateKeyException("event"));
        assertThat(service.recordSettlementFinalized(7L, 10L)).isFalse();
    }

    @Test
    void nonDuplicateIntegrityErrorsRemainVisible() {
        ready();
        when(mapper.insertEvent(
                        eq(1L),
                        eq(NotificationType.SETTLEMENT_FINALIZED),
                        eq("SETTLEMENT_BATCH"),
                        eq(10L),
                        anyString(),
                        anyString(),
                        eq(now)))
                .thenThrow(new DataIntegrityViolationException("constraint"));
        assertThatThrownBy(() -> service.recordSettlementFinalized(7L, 10L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void existingEventSkipsRepeatedConsentReads() {
        when(mapper.existsSettlementFinalized(1L, 10L)).thenReturn(true);
        assertThat(service.recordSettlementFinalized(7L, 10L)).isFalse();
        verify(mapper, never()).selectDeliveryTarget(anyLong(), any(NotificationConsentType.class));
    }
}
