package com.app.maria.domain.customer.service;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Opt-in integration against local Redis; touches only unique test signup keys. */
@EnabledIfEnvironmentVariable(named = "SIGNUP_REDIS_TEST_HOST", matches = ".+")
class RedisSignupCodeStoreTest {
    LettuceConnectionFactory factory;
    StringRedisTemplate redis;
    RedisSignupCodeStore store;
    String key;
    String prefix;

    @BeforeEach
    void setup() {
        var config =
                new RedisStandaloneConfiguration(System.getenv("SIGNUP_REDIS_TEST_HOST"), 6379);
        String password = System.getenv("SIGNUP_REDIS_TEST_PASSWORD");
        if (password != null && !password.isBlank()) config.setPassword(password);
        factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        prefix = "test:signup-code:" + UUID.randomUUID() + ":";
        store = new RedisSignupCodeStore(redis, prefix);
        key = "key";
    }

    @AfterEach
    void cleanup() {
        var keys = new java.util.ArrayList<String>();
        try (var cursor =
                redis.scan(
                        org.springframework.data.redis.core.ScanOptions.scanOptions()
                                .match(prefix + "*")
                                .count(100)
                                .build())) {
            cursor.forEachRemaining(keys::add);
        }
        if (!keys.isEmpty()) redis.delete(keys);
        factory.destroy();
    }

    private boolean reserveSend(String browser, String address) {
        return store.reserveSend(browser, address, key + ":ip");
    }

    @Test
    void everyQuotaRejectsBeforeConsumingCooldownsAndOtherCounters() {
        var limits =
                java.util.Map.of(
                        prefix + "address:day", SignupVerificationPolicy.RECIPIENT_DAILY_LIMIT,
                        prefix + key + ":ip:hour", SignupVerificationPolicy.IP_HOURLY_LIMIT,
                        prefix + key + ":ip:day", SignupVerificationPolicy.IP_DAILY_LIMIT,
                        prefix + "global:day", SignupVerificationPolicy.GLOBAL_DAILY_LIMIT);
        for (var limit : limits.entrySet()) {
            redis.opsForValue().set(limit.getKey(), Integer.toString(limit.getValue()));
            String before = redis.opsForValue().get(prefix + "global:day");
            assertThat(reserveSend("browser", "address")).isFalse();
            assertThat(redis.hasKey(prefix + "browser:cooldown")).isFalse();
            assertThat(redis.hasKey(prefix + "address:cooldown")).isFalse();
            assertThat(redis.opsForValue().get(prefix + "global:day")).isEqualTo(before);
            redis.delete(limit.getKey());
        }
        assertThat(reserveSend("browser", "address")).isTrue();
        assertThat(redis.opsForValue().get(prefix + "global:day")).isEqualTo("1");
        assertThat(redis.getExpire(prefix + "address:day")).isBetween(86390L, 86400L);
        assertThat(redis.getExpire(prefix + key + ":ip:hour")).isBetween(3590L, 3600L);
    }

    @Test
    void anotherBrowserCannotReplaceTheFirstBrowsersCode() {
        var observedStore = org.mockito.Mockito.spy(store);
        var mail = org.mockito.Mockito.mock(CustomerSignupEmailSender.class);
        var properties = new com.app.maria.global.config.properties.CiProperties();
        properties.setPepper("test-pepper");
        var service =
                new CustomerSignupVerificationService(
                        observedStore, mail, java.time.Clock.systemUTC(), properties);
        var first = service.prepare("browser-a", "user@example.com", "127.0.0.1");
        var recipient = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(observedStore)
                .reserveSend(
                        org.mockito.ArgumentMatchers.anyString(),
                        recipient.capture(),
                        org.mockito.ArgumentMatchers.anyString());
        redis.delete(prefix + recipient.getValue() + ":cooldown");
        var second = service.prepare("browser-b", "user@example.com", "127.0.0.1");
        assertThat(first.pending().identity()).isNotEqualTo(second.pending().identity());
        assertThat(service.verify(first.pending(), first.code()).verified()).isTrue();
        assertThat(service.verify(second.pending(), second.code()).verified()).isTrue();
        org.mockito.Mockito.verifyNoInteractions(mail);
    }

    @Test
    void rejectedAddressOrBrowserDoesNotConsumeTheOtherCooldown() {
        assertThat(reserveSend(key + ":browser-a", key + ":address-a")).isTrue();
        assertThat(reserveSend(key + ":browser-b", key + ":address-a")).isFalse();
        assertThat(reserveSend(key + ":browser-b", key + ":address-b")).isTrue();
        assertThat(reserveSend(key + ":browser-a", key + ":address-c")).isFalse();
        assertThat(reserveSend(key + ":browser-c", key + ":address-c")).isTrue();
        assertThat(redis.getExpire(prefix + key + ":browser-c:cooldown"))
                .isBetween(50L, SignupVerificationPolicy.RESEND_COOLDOWN_SECONDS);
    }

    @Test
    void concurrentReservationHasOneWinnerAndLosingBrowserCanUseAnotherAddress() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var first =
                    executor.submit(
                            () -> {
                                start.await();
                                return reserveSend(key + ":browser-a", key + ":address-a");
                            });
            var second =
                    executor.submit(
                            () -> {
                                start.await();
                                return reserveSend(key + ":browser-b", key + ":address-a");
                            });
            start.countDown();
            boolean a = first.get(5, TimeUnit.SECONDS), b = second.get(5, TimeUnit.SECONDS);
            assertThat(List.of(a, b)).containsExactlyInAnyOrder(true, false);
            assertThat(reserveSend(key + (a ? ":browser-b" : ":browser-a"), key + ":address-b"))
                    .isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cooldownExpiryAndFifthWrongAttemptAreEnforced() {
        assertThat(reserveSend(key + ":browser", key + ":address")).isTrue();
        assertThat(reserveSend(key + ":browser", key + ":address")).isFalse();
        store.save(key, "token", "correct");
        assertThat(redis.getExpire(prefix + key)).isBetween(290L, 300L);
        for (int i = 0; i < 5; i++) assertThat(store.consume(key, "token", "wrong")).isFalse();
        assertThat(store.consume(key, "token", "correct")).isFalse();
    }

    @Test
    void resendRevokesOldCodeAndForeignTokenCannotConsumeAttempts() {
        store.save(key, "old", "old-digest");
        store.save(key, "new", "new-digest");
        for (int i = 0; i < 6; i++) assertThat(store.consume(key, "old", "old-digest")).isFalse();
        store.cancel(key, "old");
        assertThat(store.consume(key, "new", "new-digest")).isTrue();
        assertThat(store.consume(key, "new", "new-digest")).isFalse();
    }

    @Test
    void concurrentVerificationHasExactlyOneWinner() throws Exception {
        store.save(key, "token", "digest");
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        Callable<Boolean> attempt =
                () -> {
                    start.await();
                    return store.consume(key, "token", "digest");
                };
        try {
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            start.countDown();
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredKeyCannotBeConsumed() {
        store.save(key, "token", "digest");
        redis.expire(prefix + key, java.time.Duration.ZERO);
        assertThat(store.consume(key, "token", "digest")).isFalse();
    }
}
