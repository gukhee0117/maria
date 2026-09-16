package com.app.maria.domain.withdrawal.exception;

public class EarlyWithdrawalConsentRequiredException extends WithdrawalException {
    public static final String CODE = "EARLY_WITHDRAWAL_CONSENT_REQUIRED";

    public EarlyWithdrawalConsentRequiredException(String message) {
        super(message);
    }
}
