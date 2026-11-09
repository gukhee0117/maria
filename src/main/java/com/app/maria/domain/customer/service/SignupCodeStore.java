package com.app.maria.domain.customer.service;

/** Redis operations are atomic across browser sessions and application instances. */
public interface SignupCodeStore {
    boolean reserveSend(String browserKey, String addressKey, String ipKey);

    void save(String key, String token, String digest);

    boolean consume(String key, String token, String digest);

    void cancel(String key, String token);
}
