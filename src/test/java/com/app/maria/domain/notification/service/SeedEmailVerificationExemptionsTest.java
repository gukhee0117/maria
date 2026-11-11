package com.app.maria.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.app.maria.domain.notification.mapper.CustomerNotificationSettingsMapper;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;

class SeedEmailVerificationExemptionsTest {
    @Test
    @SuppressWarnings("unchecked")
    void settingsGetAndVerificationStatusReadRegisteredEmailOnceEach() {
        var contacts = mock(CustomerNotificationSettingsMapper.class);
        var redis = mock(StringRedisTemplate.class);
        var senders = (ObjectProvider<JavaMailSender>) mock(ObjectProvider.class);
        var verification =
                new NotificationEmailVerificationService(
                        contacts, redis, senders, "", Clock.systemUTC());
        when(contacts.selectEmail(2000L)).thenReturn("new@example.com");
        when(redis.hasKey(anyString())).thenReturn(true);
        var settings =
                new CustomerNotificationSettingsServiceImpl(
                        contacts,
                        mock(com.app.maria.global.clock.service.BusinessClockService.class),
                        verification);
        assertThat(settings.get(2000L).email()).isTrue();
        verify(contacts, times(1)).selectEmail(2000L);
        clearInvocations(contacts);
        assertThat(verification.status(2000L).verified()).isTrue();
        verify(contacts, times(1)).selectEmail(2000L);
        verify(contacts, never()).selectLocalLoginId(anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void changedSeedAndUnverifiedNewCustomerNeverReachSmtp() {
        var contacts = mock(CustomerNotificationSettingsMapper.class);
        var redis = mock(StringRedisTemplate.class);
        var senders = (ObjectProvider<JavaMailSender>) mock(ObjectProvider.class);
        var sender = mock(JavaMailSender.class);
        when(senders.getIfAvailable()).thenReturn(sender);
        var guard =
                new NotificationEmailVerificationService(
                        contacts, redis, senders, "from@example.com", Clock.systemUTC());
        var renderer = mock(NotificationEmailRenderer.class);
        var mail = new NotificationEmailService(senders, "from@example.com", renderer, guard);
        when(contacts.selectEmail(1L)).thenReturn("changed@example.com");
        when(contacts.selectLocalLoginId(1L)).thenReturn("user000001");
        when(contacts.selectEmail(2000L)).thenReturn("new@example.com");
        var details =
                new com.app.maria.domain.notification.dto.SettlementEmailDetails(
                        java.math.BigDecimal.ONE, java.time.LocalDateTime.of(2026, 11, 11, 12, 0));
        mail.deliverSettlement(
                new com.app.maria.domain.notification.event.CustomerNotificationCreated(
                        1L, "done", "body", true, "changed@example.com", details));
        mail.deliverSettlement(
                new com.app.maria.domain.notification.event.CustomerNotificationCreated(
                        2000L, "done", "body", true, "new@example.com", details));
        verifyNoInteractions(sender, renderer);
        when(contacts.selectEmail(1L)).thenReturn("user000001@example.com");
        when(contacts.selectLocalLoginId(1L)).thenReturn("newly-registered-login");
        assertThat(guard.isVerified(1L, "user000001@example.com")).isFalse();
    }

    private final SeedEmailVerificationExemptions exemptions =
            new SeedEmailVerificationExemptions();

    @Test
    void onlyOriginalSeedCustomerAndLoginPairIsExempt() {
        assertThat(exemptions.matches(1L, "user000001", "user000001@example.com")).isTrue();
        assertThat(exemptions.matches(1L, "newuser", "user000001@example.com")).isFalse();
        assertThat(exemptions.matches(2000L, "user000001", "user000001@example.com")).isFalse();
        assertThat(exemptions.matches(1L, "user000001", null)).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void seedBypassStillRequiresTheCurrentRegisteredAddressAndNewUsersNeedProof() {
        var contacts = mock(CustomerNotificationSettingsMapper.class);
        var redis = mock(StringRedisTemplate.class);
        var senders = (ObjectProvider<JavaMailSender>) mock(ObjectProvider.class);
        var service =
                new NotificationEmailVerificationService(
                        contacts, redis, senders, "", Clock.systemUTC());
        when(contacts.selectEmail(1L)).thenReturn("user000001@example.com");
        when(contacts.selectLocalLoginId(1L)).thenReturn("user000001");
        assertThat(service.isVerified(1L, "user000001@example.com")).isTrue();
        verifyNoInteractions(redis);
        when(contacts.selectEmail(1L)).thenReturn("changed@example.com");
        assertThat(service.isVerified(1L, "user000001@example.com")).isFalse();
        assertThat(service.isVerified(1L, "changed@example.com")).isFalse();
        when(contacts.selectEmail(2000L)).thenReturn("user000001@example.com");
        assertThat(service.isVerified(2000L, "user000001@example.com")).isFalse();
        verify(contacts, never()).selectLocalLoginId(2000L);
        when(redis.hasKey(anyString())).thenReturn(true);
        assertThat(service.isVerified(2000L, "user000001@example.com")).isTrue();
    }

    @Test
    void missingOrMalformedResourceDisablesOnlySeedExceptions() {
        var missing =
                new SeedEmailVerificationExemptions(
                        new org.springframework.core.io.ClassPathResource("missing-seed-resource"));
        assertThat(missing.containsCustomer(1L)).isFalse();
        var malformed =
                new SeedEmailVerificationExemptions(
                        new org.springframework.core.io.ByteArrayResource(
                                "bad:data".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(malformed.containsCustomer(1L)).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisOutageDoesNotAuthenticateAnUnverifiedAddressOrThrow() {
        var contacts = mock(CustomerNotificationSettingsMapper.class);
        var redis = mock(StringRedisTemplate.class);
        var senders = (ObjectProvider<JavaMailSender>) mock(ObjectProvider.class);
        var service =
                new NotificationEmailVerificationService(
                        contacts, redis, senders, "", Clock.systemUTC());
        when(contacts.selectEmail(2000L)).thenReturn("new@example.com");
        when(redis.hasKey(anyString()))
                .thenThrow(
                        new org.springframework.data.redis.RedisConnectionFailureException(
                                "offline"));
        assertThat(service.status(2000L).verified()).isFalse();
        verify(contacts, never()).selectLocalLoginId(anyLong());
    }
}
