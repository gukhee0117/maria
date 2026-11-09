package com.app.maria.domain.customer.service;

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
    private static final DefaultRedisScript<Long> RESERVE =
            new DefaultRedisScript<>(
                    """
            if redis.call('EXISTS', KEYS[1]) == 1 or redis.call('EXISTS', KEYS[2]) == 1 then return 0 end
            redis.call('SET', KEYS[1], '1', 'EX', ARGV[1])
            redis.call('SET', KEYS[2], '1', 'EX', ARGV[1])
            return 1
            """,
                    Long.class);
    private static final DefaultRedisScript<Long> SAVE =
            new DefaultRedisScript<>(
                    """
            redis.call('HSET', KEYS[1], 'token', ARGV[1], 'digest', ARGV[2], 'attempts', 0)
            redis.call('EXPIRE', KEYS[1], ARGV[3])
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
            if attempts >= tonumber(ARGV[3]) then redis.call('DEL', KEYS[1]) end
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
    public boolean reserveSend(String browserKey, String addressKey) {
        return Long.valueOf(1)
                .equals(
                        redis.execute(
                                RESERVE,
                                List.of(
                                        PREFIX + browserKey + ":cooldown",
                                        PREFIX + addressKey + ":cooldown"),
                                Long.toString(SignupVerificationPolicy.RESEND_COOLDOWN_SECONDS)));
    }

    @Override
    public void save(String key, String token, String digest) {
        redis.execute(
                SAVE,
                List.of(PREFIX + key),
                token,
                digest,
                Long.toString(SignupVerificationPolicy.CODE_TTL_SECONDS));
    }

    @Override
    public boolean consume(String key, String token, String digest) {
        return Long.valueOf(1)
                .equals(
                        redis.execute(
                                CONSUME,
                                List.of(PREFIX + key),
                                token,
                                digest,
                                Integer.toString(SignupVerificationPolicy.MAX_ATTEMPTS)));
    }

    @Override
    public void cancel(String key, String token) {
        redis.execute(CANCEL, List.of(PREFIX + key), token);
    }
}
