package com.app.maria.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.app.maria.domain.notification.dto.CustomerNotificationConsentDTO;
import com.app.maria.domain.notification.dto.CustomerNotificationSettingsRequestDTO;
import com.app.maria.domain.notification.mapper.CustomerNotificationSettingsMapper;
import com.app.maria.domain.notification.type.NotificationConsentType;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomerNotificationSettingsServiceImplTest {
    @Mock private CustomerNotificationSettingsMapper mapper;
    @Mock private BusinessClockService clock;
    @Mock private NotificationEmailVerificationService verification;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 8, 10, 0);
    @InjectMocks private CustomerNotificationSettingsServiceImpl service;

    @Test
    void missingEmailRejectsEntireUpdateBeforeAnyConsentIsSaved() {
        when(mapper.lockCustomer(45L)).thenReturn(45L);
        when(mapper.hasRegisteredEmail(45L)).thenReturn(false);
        assertThatThrownBy(() -> service.update(45L, request(true)))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.CUSTOMER_NOTIFICATION_EMAIL_REQUIRED));
        verify(mapper, never()).insertConsent(anyLong(), any(), anyBoolean(), any());
        verify(mapper, never()).selectLatestByCustomerId(anyLong());
    }

    @Test
    void registeredEmailAllowsEnableAndReturnsSavedSetting() {
        when(mapper.lockCustomer(45L)).thenReturn(45L);
        when(clock.now()).thenReturn(NOW);
        when(mapper.hasRegisteredEmail(45L)).thenReturn(true);
        when(mapper.selectEmail(45L)).thenReturn("test@example.com");
        when(verification.isVerified(45L, "test@example.com")).thenReturn(true);
        CustomerNotificationConsentDTO row = new CustomerNotificationConsentDTO();
        row.setConsentType(NotificationConsentType.NOTIFY_EMAIL);
        row.setAgreed(true);
        when(mapper.selectLatestByCustomerId(45L)).thenReturn(List.of(), List.of(row));

        assertThat(service.update(45L, request(true)).email()).isTrue();

        verify(mapper).insertConsent(45L, NotificationConsentType.NOTIFY_EMAIL, true, NOW);
        verify(mapper, times(1)).insertConsent(eq(45L), any(), anyBoolean(), eq(NOW));
    }

    @Test
    void disablingEmailAndChangingOtherSettingsDoesNotRequireRegisteredEmail() {
        when(mapper.lockCustomer(45L)).thenReturn(45L);
        when(clock.now()).thenReturn(NOW);
        CustomerNotificationConsentDTO row = new CustomerNotificationConsentDTO();
        row.setConsentType(NotificationConsentType.NOTIFY_EMAIL);
        row.setAgreed(true);
        when(mapper.selectLatestByCustomerId(45L)).thenReturn(List.of(row), List.of());
        service.update(45L, request(false));
        verify(mapper, never()).hasRegisteredEmail(anyLong());
        verify(mapper).insertConsent(45L, NotificationConsentType.NOTIFY_EMAIL, false, NOW);
        verify(mapper, times(1)).insertConsent(eq(45L), any(), anyBoolean(), eq(NOW));
    }

    @Test
    void unverifiedEmailCannotEnableNotifications() {
        when(mapper.lockCustomer(45L)).thenReturn(45L);
        when(mapper.hasRegisteredEmail(45L)).thenReturn(true);
        when(mapper.selectEmail(45L)).thenReturn("test@example.com");
        assertThatThrownBy(() -> service.update(45L, request(true)))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(
                                                ErrorType
                                                        .NOTIFICATION_EMAIL_VERIFICATION_REQUIRED));
        verify(mapper, never()).insertConsent(anyLong(), any(), anyBoolean(), any());
    }

    private CustomerNotificationSettingsRequestDTO request(boolean email) {
        return new CustomerNotificationSettingsRequestDTO(
                true, false, email, true, true, true, true);
    }
}
