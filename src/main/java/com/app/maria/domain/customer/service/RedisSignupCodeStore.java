package com.app.maria.domain.customer.service;

import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RedisSignupCodeStore implements SignupCodeStore {
    private final StringRedisTemplate redis;
    private static final String PREFIX = "customer:signup-code:";
    private static final DefaultRedisScript<Long> SAVE =
            new DefaultRedisScript<>(
                    """
            redis.call('HSET', KEYS[1], 'token', ARGV[1], 'digest', ARGV[2], 'attempts', 0)
            redis.call('EXPIRE', KEYS[1], 300)
            return 1
            """,
                    Long.class);
    private static final DefaultRedisScript<Long> CONSUME =
            new DefaultRedisScript<>(
                    """
            if redis.call('HGET', KEYS[1], 'token') ~= ARGV[1] then return 0 end
            local attempts = redis.call('HINCRBY', KEYS[1], 'attempts', 1)
            if redis.call('HGET', KEYS[1], 'digest') == ARGV[2] then
                redis.call('DEL', KEYS[1])
                return 1
            end
            if attempts >= 5 then redis.call('DEL', KEYS[1]) end
            return 0
            """,
                    Long.class);
    private static final DefaultRedisScript<Long> CANCEL =
            new DefaultRedisScript<>(
                    """
            if redis.call('HGET', KEYS[1], 'token') == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """,
                    Long.class);

    @Override
    public boolean reserveSend(String customerId) {
        return Boolean.TRUE.equals(
                redis.opsForValue()
                        .setIfAbsent(
                                PREFIX + customerId + ":cooldown", "1", Duration.ofSeconds(60)));
    }

    @Override
    public void save(String customerId, String token, String digest) {
        redis.execute(SAVE, List.of(PREFIX + customerId), token, digest);
    }

    @Override
    public boolean consume(String customerId, String token, String digest) {
        return Long.valueOf(1)
                .equals(redis.execute(CONSUME, List.of(PREFIX + customerId), token, digest));
    }

    @Override
    public void cancel(String customerId, String token) {
        redis.execute(CANCEL, List.of(PREFIX + customerId), token);
    }
}
