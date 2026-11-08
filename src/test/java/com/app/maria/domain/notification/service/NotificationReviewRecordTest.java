package com.app.maria.domain.notification.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.notification.mapper.CustomerNotificationMapper;
import com.app.maria.global.clock.service.BusinessClockService;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class NotificationReviewRecordTest {
    @Mock CustomerNotificationMapper mapper;
    @Mock AccountMapper accounts;
    @Mock CustomerNotificationSettingsService settings;
    @Mock BusinessClockService clock;
    @InjectMocks CustomerNotificationServiceImpl service;
    private final LocalDateTime now = LocalDateTime.of(2026, 11, 8, 10, 0);

    @BeforeEach
    void setup() {
        when(accounts.selectByAccountId(7L))
                .thenReturn(Optional.of(AccountDTO.builder().customerId(1L).build()));
    }

    @Test
    void concurrentDuplicateIsTheOnlySuppressedInsertError() {
        when(settings.isSettlementPushEnabled(1L)).thenReturn(true);
        when(mapper.insertSettlementFinalized(eq(1L), eq(10L), anyString(), anyString(), eq(now)))
                .thenThrow(new DuplicateKeyException("event"));
        assertThat(service.recordSettlementFinalized(7L, 10L, now)).isFalse();
    }

    @Test
    void nonDuplicateIntegrityErrorsRemainVisible() {
        when(settings.isSettlementPushEnabled(1L)).thenReturn(true);
        when(mapper.insertSettlementFinalized(eq(1L), eq(10L), anyString(), anyString(), eq(now)))
                .thenThrow(new DataIntegrityViolationException("constraint"));
        assertThatThrownBy(() -> service.recordSettlementFinalized(7L, 10L, now))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void existingEventSkipsRepeatedConsentReads() {
        when(mapper.existsSettlementFinalized(1L, 10L)).thenReturn(true);
        assertThat(service.recordSettlementFinalized(7L, 10L, now)).isFalse();
        verifyNoInteractions(settings);
    }
}
