package com.app.maria.domain.accountclosure.exception;

public class AccountClosureNotAllowedException extends AccountClosureException {
    public static final String CODE = "ACCOUNT_CLOSURE_NOT_ALLOWED";

    public AccountClosureNotAllowedException(String message) {
        super(message);
    }
}
