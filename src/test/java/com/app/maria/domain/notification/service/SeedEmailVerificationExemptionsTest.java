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
    void onlyOriginalSeedCustomerAndLoginPairIsExempt() {
        assertThat(SeedEmailVerificationExemptions.matches(1L, "user000001")).isTrue();
        assertThat(SeedEmailVerificationExemptions.matches(1L, "newuser")).isFalse();
        assertThat(SeedEmailVerificationExemptions.matches(2000L, "user000001")).isFalse();
        assertThat(SeedEmailVerificationExemptions.matches(1L, null)).isFalse();
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
        assertThat(service.isVerified(1L, "changed@example.com")).isTrue();
        when(contacts.selectEmail(2000L)).thenReturn("user000001@example.com");
        assertThat(service.isVerified(2000L, "user000001@example.com")).isFalse();
        when(redis.hasKey(anyString())).thenReturn(true);
        assertThat(service.isVerified(2000L, "user000001@example.com")).isTrue();
    }
}
