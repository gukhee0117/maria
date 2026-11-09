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

    @BeforeEach
    void setup() {
        var config =
                new RedisStandaloneConfiguration(System.getenv("SIGNUP_REDIS_TEST_HOST"), 6379);
        String password = System.getenv("SIGNUP_REDIS_TEST_PASSWORD");
        if (password != null && !password.isBlank()) config.setPassword(password);
        factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        store = new RedisSignupCodeStore(redis);
        key = "test:" + UUID.randomUUID();
    }

    @AfterEach
    void cleanup() {
        var keys = new java.util.ArrayList<String>();
        keys.add("customer:signup-code:" + key);
        for (String suffix :
                List.of(
                        ":browser",
                        ":address",
                        ":browser-a",
                        ":browser-b",
                        ":browser-c",
                        ":address-a",
                        ":address-b",
                        ":address-c")) {
            keys.add("customer:signup-code:" + key + suffix + ":cooldown");
        }
        redis.delete(keys);
        factory.destroy();
    }

    @Test
    void rejectedAddressOrBrowserDoesNotConsumeTheOtherCooldown() {
        assertThat(store.reserveSend(key + ":browser-a", key + ":address-a")).isTrue();
        assertThat(store.reserveSend(key + ":browser-b", key + ":address-a")).isFalse();
        assertThat(store.reserveSend(key + ":browser-b", key + ":address-b")).isTrue();
        assertThat(store.reserveSend(key + ":browser-a", key + ":address-c")).isFalse();
        assertThat(store.reserveSend(key + ":browser-c", key + ":address-c")).isTrue();
        assertThat(redis.getExpire("customer:signup-code:" + key + ":browser-c:cooldown"))
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
                                return store.reserveSend(key + ":browser-a", key + ":address-a");
                            });
            var second =
                    executor.submit(
                            () -> {
                                start.await();
                                return store.reserveSend(key + ":browser-b", key + ":address-a");
                            });
            start.countDown();
            boolean a = first.get(5, TimeUnit.SECONDS), b = second.get(5, TimeUnit.SECONDS);
            assertThat(List.of(a, b)).containsExactlyInAnyOrder(true, false);
            assertThat(
                            store.reserveSend(
                                    key + (a ? ":browser-b" : ":browser-a"), key + ":address-b"))
                    .isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cooldownExpiryAndFifthWrongAttemptAreEnforced() {
        assertThat(store.reserveSend(key + ":browser", key + ":address")).isTrue();
        assertThat(store.reserveSend(key + ":browser", key + ":address")).isFalse();
        store.save(key, "token", "correct");
        assertThat(redis.getExpire("customer:signup-code:" + key)).isBetween(290L, 300L);
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
        redis.expire("customer:signup-code:" + key, java.time.Duration.ZERO);
        assertThat(store.consume(key, "token", "digest")).isFalse();
    }
}
