package com.app.maria.domain.withdrawal.exception;

public class WithdrawalNotAllowedException extends RuntimeException {
    public WithdrawalNotAllowedException(String message) {
        super(message);
    }

}
