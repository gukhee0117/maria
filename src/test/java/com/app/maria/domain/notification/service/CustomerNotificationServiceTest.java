package com.app.maria.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.notification.dto.NotificationDeliveryDTO;
import com.app.maria.domain.notification.event.CustomerNotificationCreated;
import com.app.maria.domain.notification.mapper.CustomerNotificationMapper;
import com.app.maria.domain.notification.type.NotificationConsentType;
import com.app.maria.domain.notification.type.NotificationType;
import com.app.maria.global.clock.service.BusinessClockService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class CustomerNotificationServiceTest {
    @Mock CustomerNotificationMapper mapper;
    @Mock AccountMapper accounts;
    @Mock BusinessClockService clock;
    @Mock ApplicationEventPublisher events;
    @InjectMocks CustomerNotificationServiceImpl service;
    private final LocalDateTime now = LocalDateTime.of(2026, 11, 8, 10, 0);

    private NotificationDeliveryDTO target(boolean push, boolean email, boolean kind) {
        NotificationDeliveryDTO target = new NotificationDeliveryDTO();
        target.setPushEnabled(push);
        target.setEmailEnabled(email);
        target.setKindEnabled(kind);
        target.setEmail("test@example.com");
        return target;
    }

    @Test
    void selfCommentOrAiParentWithoutCustomerDoesNotNotify() {
        service.recordReply(1L, 1L, 10L, false);
        service.recordReply(null, 2L, 10L, false);
        verifyNoInteractions(mapper, events, clock);
    }

    @Test
    void customerReplyRecordsEventWithBusinessTimeAndMatchingPreference() {
        when(clock.now()).thenReturn(now);
        when(mapper.selectDeliveryTarget(1L, NotificationConsentType.NOTIFY_COMMENT))
                .thenReturn(target(true, false, true));
        when(mapper.insertEvent(
                        eq(1L),
                        eq(NotificationType.COMMUNITY_COMMENT),
                        eq("COMMUNITY_COMMENT"),
                        eq(10L),
                        anyString(),
                        anyString(),
                        eq(now)))
                .thenReturn(1);
        service.recordReply(1L, 2L, 10L, false);
        ArgumentCaptor<CustomerNotificationCreated> event =
                ArgumentCaptor.forClass(CustomerNotificationCreated.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().customerId()).isEqualTo(1L);
        assertThat(event.getValue().pushEnabled()).isTrue();
        assertThat(event.getValue().email()).isNull();
    }

    @Test
    void kiruUsesIndependentSettingAndSkipsDisabledKind() {
        when(clock.now()).thenReturn(now);
        when(mapper.selectDeliveryTarget(1L, NotificationConsentType.NOTIFY_KIRU))
                .thenReturn(target(true, true, false));
        service.recordReply(1L, null, 11L, true);
        verify(mapper, never())
                .insertEvent(
                        anyLong(),
                        any(NotificationType.class),
                        anyString(),
                        anyLong(),
                        anyString(),
                        anyString(),
                        any());
        verifyNoInteractions(events);
    }

    @Test
    void emailOnlySendsNoPushAndReprocessingDoesNotRepublish() {
        when(clock.now()).thenReturn(now);
        when(mapper.selectDeliveryTarget(1L, NotificationConsentType.NOTIFY_KIRU))
                .thenReturn(target(false, true, true));
        when(mapper.insertEvent(
                        eq(1L),
                        eq(NotificationType.KIRU_REPLY),
                        anyString(),
                        eq(11L),
                        anyString(),
                        anyString(),
                        eq(now)))
                .thenReturn(1, 0);
        service.recordReply(1L, null, 11L, true);
        service.recordReply(1L, null, 11L, true);
        ArgumentCaptor<CustomerNotificationCreated> event =
                ArgumentCaptor.forClass(CustomerNotificationCreated.class);
        verify(events, times(1)).publishEvent(event.capture());
        assertThat(event.getValue().pushEnabled()).isFalse();
        assertThat(event.getValue().email()).isEqualTo("test@example.com");
    }

    @Test
    void noChannelDoesNotCreateNotification() {
        when(clock.now()).thenReturn(now);
        when(mapper.selectDeliveryTarget(1L, NotificationConsentType.NOTIFY_COMMENT))
                .thenReturn(target(false, false, true));
        service.recordReply(1L, 2L, 10L, false);
        verify(mapper, never())
                .insertEvent(
                        anyLong(),
                        any(NotificationType.class),
                        anyString(),
                        anyLong(),
                        anyString(),
                        anyString(),
                        any());
        verifyNoInteractions(events);
    }

    @Test
    void settlementSupportsEmailWithoutRequiringAppPush() {
        when(mapper.selectSettlementEmailDetails(3L, 20L))
                .thenReturn(
                        new com.app.maria.domain.notification.dto.SettlementEmailDetails(
                                new java.math.BigDecimal("1234567"), now));
        when(accounts.selectByAccountId(3L))
                .thenReturn(java.util.Optional.of(AccountDTO.builder().customerId(1L).build()));
        when(mapper.selectDeliveryTarget(1L, NotificationConsentType.NOTIFY_SETTLEMENT))
                .thenReturn(target(false, true, true));
        when(mapper.insertEvent(
                        eq(1L),
                        eq(NotificationType.SETTLEMENT_FINALIZED),
                        anyString(),
                        eq(20L),
                        anyString(),
                        anyString(),
                        eq(now)))
                .thenReturn(1);
        assertThat(service.recordSettlementFinalized(3L, 20L, now)).isFalse();
        verify(events).publishEvent(any(CustomerNotificationCreated.class));
    }

    @Test
    void missingSettlementSummaryDoesNotPublishAnIncorrectAmount() {
        when(accounts.selectByAccountId(3L))
                .thenReturn(java.util.Optional.of(AccountDTO.builder().customerId(1L).build()));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.recordSettlementFinalized(3L, 20L, now))
                .isInstanceOf(com.app.maria.global.exception.AppException.class);
        verifyNoInteractions(events);
        verify(mapper, never())
                .insertEvent(
                        anyLong(),
                        any(NotificationType.class),
                        anyString(),
                        anyLong(),
                        anyString(),
                        anyString(),
                        any());
    }
}
