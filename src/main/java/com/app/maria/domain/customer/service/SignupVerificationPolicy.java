package com.app.maria.domain.customer.service;

/** Shared security timing for the API challenge and Redis storage. */
public final class SignupVerificationPolicy {
    public static final long CODE_TTL_SECONDS = 300;
    public static final long RESEND_COOLDOWN_SECONDS = 60;
    public static final long VERIFIED_TTL_SECONDS = 600;
    public static final int MAX_ATTEMPTS = 5;

    public static final long HOUR_SECONDS = 3600;
    public static final long DAY_SECONDS = 86400;
    public static final int RECIPIENT_DAILY_LIMIT = 10;
    public static final int IP_HOURLY_LIMIT = 20;
    public static final int IP_DAILY_LIMIT = 100;
    public static final int GLOBAL_DAILY_LIMIT = 200;

    public static String codeValidityText() {
        return CODE_TTL_SECONDS % 60 == 0 ? CODE_TTL_SECONDS / 60 + "분" : CODE_TTL_SECONDS + "초";
    }

    private SignupVerificationPolicy() {}
}
