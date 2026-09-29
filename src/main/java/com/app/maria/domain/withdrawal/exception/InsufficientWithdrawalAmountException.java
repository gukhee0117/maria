package com.app.maria.domain.withdrawal.exception;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;

@Getter
public class InsufficientWithdrawalAmountException extends RuntimeException {

    private final Long accountId;
    private final BigDecimal requestedAmount;
    private final LocalDateTime failedAt;
    private final String destinationAccountNo;
    private final Long destinationGeneralAccountId;

    public InsufficientWithdrawalAmountException(String message) {
        this(message, null, null, null, null, null);
    }

    public InsufficientWithdrawalAmountException(
            String message,
            Long accountId,
            BigDecimal requestedAmount,
            LocalDateTime failedAt,
            String destinationAccountNo,
            Long destinationGeneralAccountId) {
        super(message);
        this.accountId = accountId;
        this.requestedAmount = requestedAmount;
        this.failedAt = failedAt;
        this.destinationAccountNo = destinationAccountNo;
        this.destinationGeneralAccountId = destinationGeneralAccountId;
    }
}
