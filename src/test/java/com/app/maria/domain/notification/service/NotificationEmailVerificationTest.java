package com.app.maria.domain.notification.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.app.maria.domain.notification.mapper.CustomerNotificationSettingsMapper;
import com.app.maria.global.exception.AppException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

@EnabledIfEnvironmentVariable(named = "NOTIFICATION_REDIS_TEST_HOST", matches = ".+")
class NotificationEmailVerificationTest {
    LettuceConnectionFactory factory;
    StringRedisTemplate redis;
    NotificationEmailVerificationService verification;
    CustomerNotificationSettingsMapper contacts = mock(CustomerNotificationSettingsMapper.class);
    JavaMailSender sender = mock(JavaMailSender.class);
    Long id;
    String email;
    String code;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        id = -Math.abs(UUID.randomUUID().getMostSignificantBits());
        email = UUID.randomUUID() + "@example.com";
        var config =
                new RedisStandaloneConfiguration(
                        System.getenv("NOTIFICATION_REDIS_TEST_HOST"), 6379);
        String password = System.getenv("NOTIFICATION_REDIS_TEST_PASSWORD");
        if (password != null && !password.isBlank()) config.setPassword(password);
        factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        ObjectProvider<JavaMailSender> providers = mock(ObjectProvider.class);
        when(providers.getIfAvailable()).thenReturn(sender);
        when(contacts.selectEmail(anyLong())).thenReturn(email);
        when(sender.createMimeMessage())
                .thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
        doAnswer(
                        i -> {
                            var matcher =
                                    Pattern.compile("[0-9]{6}")
                                            .matcher(
                                                    (String)
                                                            ((MimeMessage) i.getArgument(0))
                                                                    .getContent());
                            assertThat(matcher.find()).isTrue();
                            code = matcher.group();
                            return null;
                        })
                .when(sender)
                .send(any(MimeMessage.class));
        verification =
                new NotificationEmailVerificationService(
                        contacts, redis, providers, "sender@example.com", Clock.systemUTC());
    }

    @AfterEach
    void cleanup() {
        var keys = new ArrayList<String>();
        for (String address : List.of(email, "changed-" + email)) {
            for (long customer : List.of(id, id - 1)) {
                keys.add(NotificationEmailVerificationService.key("challenge", customer, address));
                keys.add(NotificationEmailVerificationService.key("verified", customer, address));
                keys.add("notification:email:request:customer:" + customer);
                keys.add("notification:email:request:hour:" + customer);
            }
            keys.add(
                    "notification:email:request:address:"
                            + NotificationEmailVerificationService.hash(address));
            keys.add(
                    "notification:email:request:day:"
                            + NotificationEmailVerificationService.hash(address));
        }
        redis.delete(keys);
        factory.destroy();
    }

    @Test
    void proofIsOneTimeAndBoundToCurrentAddress() {
        assertThat(verification.status(id).verified()).isFalse();
        verification.send(id);
        assertThat(
                        redis.getExpire(
                                NotificationEmailVerificationService.key("challenge", id, email)))
                .isBetween(290L, 300L);
        assertThat(verification.confirm(id, code).verified()).isTrue();
        assertThatThrownBy(() -> verification.confirm(id, code)).isInstanceOf(AppException.class);
        assertThat(verification.isVerified(id, email)).isTrue();
        when(contacts.selectEmail(id)).thenReturn("changed-" + email);
        assertThat(verification.isVerified(id, email)).isFalse();
        assertThat(verification.status(id).verified()).isFalse();
    }

    @Test
    void fifthIncorrectAttemptAndExpirationDestroyTheChallenge() {
        verification.send(id);
        String wrong = code.equals("000000") ? "000001" : "000000";
        for (int i = 0; i < 5; i++)
            assertThatThrownBy(() -> verification.confirm(id, wrong))
                    .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> verification.confirm(id, code)).isInstanceOf(AppException.class);
        assertThat(verification.status(id).verified()).isFalse();
        redis.delete("notification:email:request:customer:" + id);
        redis.delete(
                "notification:email:request:address:"
                        + NotificationEmailVerificationService.hash(email));
        verification.send(id);
        redis.expire(
                NotificationEmailVerificationService.key("challenge", id, email), Duration.ZERO);
        assertThatThrownBy(() -> verification.confirm(id, code)).isInstanceOf(AppException.class);
    }

    @Test
    void customerAndRecipientCooldownsApplyAcrossAccountsAndAddressChanges() {
        verification.send(id);
        assertThatThrownBy(() -> verification.send(id - 1)).isInstanceOf(AppException.class);
        when(contacts.selectEmail(id)).thenReturn("changed-" + email);
        assertThatThrownBy(() -> verification.send(id)).isInstanceOf(AppException.class);
        verify(sender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void hourlyAndDailyQuotasCannotBeBypassedByClearingShortCooldown() {
        redis.opsForValue()
                .set(
                        "notification:email:request:day:"
                                + NotificationEmailVerificationService.hash(email),
                        "10",
                        Duration.ofDays(1));
        assertThatThrownBy(() -> verification.send(id)).isInstanceOf(AppException.class);
        redis.delete(
                "notification:email:request:day:"
                        + NotificationEmailVerificationService.hash(email));
        redis.opsForValue().set("notification:email:request:hour:" + id, "10", Duration.ofHours(1));
        assertThatThrownBy(() -> verification.send(id)).isInstanceOf(AppException.class);
        verify(sender, never()).send(any(MimeMessage.class));
    }

    @Test
    void smtpFailureRevokesCodeAndMissingAddressCannotSend() {
        doThrow(new MailSendException("private response"))
                .when(sender)
                .send(any(MimeMessage.class));
        assertThatThrownBy(() -> verification.send(id))
                .isInstanceOf(AppException.class)
                .hasCause(null);
        assertThat(redis.hasKey(NotificationEmailVerificationService.key("challenge", id, email)))
                .isFalse();
        when(contacts.selectEmail(id)).thenReturn(null);
        assertThatThrownBy(() -> verification.send(id)).isInstanceOf(AppException.class);
    }
}
