package com.app.maria.domain.withdrawal.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class WithdrawalFailureContext {

    private final Long accountId;
    private final BigDecimal requestedAmount;
    private final LocalDateTime failedAt;
    private final String destinationAccountNo;
    private final Long destinationGeneralAccountId;

    @Override
    public String toString() {
        return "WithdrawalFailureContext{"
                + "accountId="
                + accountId
                + ", requestedAmount="
                + requestedAmount
                + ", failedAt="
                + failedAt
                + ", destinationGeneralAccountId="
                + destinationGeneralAccountId
                + '}';
    }
}
