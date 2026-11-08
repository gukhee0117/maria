package com.app.maria.domain.customer.service;

/** Redis operations are atomic across browser sessions and application instances. */
public interface SignupCodeStore {
    boolean reserveSend(String customerId);

    void save(String customerId, String token, String digest);

    boolean consume(String customerId, String token, String digest);

    void cancel(String customerId, String token);
}
