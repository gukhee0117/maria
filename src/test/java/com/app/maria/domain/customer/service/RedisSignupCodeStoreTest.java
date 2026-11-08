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

/** Opt-in integration against local Redis; touches only unique test customer keys. */
@EnabledIfEnvironmentVariable(named = "SIGNUP_REDIS_TEST_HOST", matches = ".+")
class RedisSignupCodeStoreTest {
    LettuceConnectionFactory factory;
    StringRedisTemplate redis;
    RedisSignupCodeStore store;
    String customerId;

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
        customerId = "test:" + UUID.randomUUID();
    }

    @AfterEach
    void cleanup() {
        redis.delete(
                List.of(
                        "customer:signup-code:" + customerId,
                        "customer:signup-code:" + customerId + ":cooldown"));
        factory.destroy();
    }

    @Test
    void cooldownExpiryAndFifthWrongAttemptAreEnforced() {
        assertThat(store.reserveSend(customerId)).isTrue();
        assertThat(store.reserveSend(customerId)).isFalse();
        store.save(customerId, "token", "correct");
        assertThat(redis.getExpire("customer:signup-code:" + customerId)).isBetween(290L, 300L);
        for (int i = 0; i < 5; i++)
            assertThat(store.consume(customerId, "token", "wrong")).isFalse();
        assertThat(store.consume(customerId, "token", "correct")).isFalse();
    }

    @Test
    void resendRevokesOldCodeAndForeignTokenCannotConsumeAttempts() {
        store.save(customerId, "old", "old-digest");
        store.save(customerId, "new", "new-digest");
        for (int i = 0; i < 6; i++)
            assertThat(store.consume(customerId, "old", "old-digest")).isFalse();
        store.cancel(customerId, "old");
        assertThat(store.consume(customerId, "new", "new-digest")).isTrue();
        assertThat(store.consume(customerId, "new", "new-digest")).isFalse();
    }

    @Test
    void concurrentVerificationHasExactlyOneWinner() throws Exception {
        store.save(customerId, "token", "digest");
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        Callable<Boolean> attempt =
                () -> {
                    start.await();
                    return store.consume(customerId, "token", "digest");
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
        store.save(customerId, "token", "digest");
        redis.expire("customer:signup-code:" + customerId, java.time.Duration.ZERO);
        assertThat(store.consume(customerId, "token", "digest")).isFalse();
    }
}
