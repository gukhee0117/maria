package com.app.maria.domain.customer.service;

/** Shared security timing for the API challenge and Redis storage. */
public final class SignupVerificationPolicy {
    public static final long CODE_TTL_SECONDS = 300;
    public static final long RESEND_COOLDOWN_SECONDS = 60;
    public static final long VERIFIED_TTL_SECONDS = 600;
    public static final int MAX_ATTEMPTS = 5;

    private SignupVerificationPolicy() {}
}
