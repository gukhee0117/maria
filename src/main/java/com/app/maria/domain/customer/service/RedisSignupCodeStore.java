package com.app.maria.domain.customer.service;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisSignupCodeStore implements SignupCodeStore {
    private final StringRedisTemplate redis;
    private final String prefix;

    @Autowired
    public RedisSignupCodeStore(StringRedisTemplate redis) {
        this(redis, "customer:signup-code:");
    }

    RedisSignupCodeStore(StringRedisTemplate redis, String prefix) {
        this.redis = redis;
        this.prefix = prefix;
    }

    private static final DefaultRedisScript<Long> RESERVE =
            new DefaultRedisScript<>(
                    """
            -- KEYS[1..2]: browser/address cooldown; KEYS[3..6]: address day, IP hour/day, global day
            -- ARGV[1]: cooldown seconds; ARGV[2..5]: limits for KEYS[3..6]
            -- ARGV[6..7]: hour/day TTL seconds
            if redis.call('EXISTS', KEYS[1]) == 1 or redis.call('EXISTS', KEYS[2]) == 1 then return 0 end
            for i = 3, 6 do
                if tonumber(redis.call('GET', KEYS[i]) or '0') >= tonumber(ARGV[i - 1]) then return 0 end
            end
            redis.call('SET', KEYS[1], '1', 'EX', ARGV[1])
            redis.call('SET', KEYS[2], '1', 'EX', ARGV[1])
            for i = 3, 6 do
                if redis.call('INCR', KEYS[i]) == 1 then
                    local ttl = ARGV[7]
                    if i == 4 then ttl = ARGV[6] end
                    redis.call('EXPIRE', KEYS[i], ttl)
                end
            end
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
    public boolean reserveSend(String browserKey, String addressKey, String ipKey) {
        return Long.valueOf(1)
                .equals(
                        redis.execute(
                                RESERVE,
                                List.of(
                                        prefix + browserKey + ":cooldown",
                                        prefix + addressKey + ":cooldown",
                                        prefix + addressKey + ":day",
                                        prefix + ipKey + ":hour",
                                        prefix + ipKey + ":day",
                                        prefix + "global:day"),
                                Long.toString(SignupVerificationPolicy.RESEND_COOLDOWN_SECONDS),
                                Integer.toString(SignupVerificationPolicy.RECIPIENT_DAILY_LIMIT),
                                Integer.toString(SignupVerificationPolicy.IP_HOURLY_LIMIT),
                                Integer.toString(SignupVerificationPolicy.IP_DAILY_LIMIT),
                                Integer.toString(SignupVerificationPolicy.GLOBAL_DAILY_LIMIT),
                                Long.toString(SignupVerificationPolicy.HOUR_SECONDS),
                                Long.toString(SignupVerificationPolicy.DAY_SECONDS)));
    }

    @Override
    public void save(String key, String token, String digest) {
        redis.execute(
                SAVE,
                List.of(prefix + key),
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
                                List.of(prefix + key),
                                token,
                                digest,
                                Integer.toString(SignupVerificationPolicy.MAX_ATTEMPTS)));
    }

    @Override
    public void cancel(String key, String token) {
        redis.execute(CANCEL, List.of(prefix + key), token);
    }
}
