package com.app.maria.domain.withdrawal.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;

@Getter
public class CustomerWithdrawalAvailabilityResponseDTO {

    private final BigDecimal cashAmount;
    private final BigDecimal provisionalAmount;
    private final BigDecimal requestableAmount;
    private final BigDecimal earningsAmount;
    private final BigDecimal withdrawableWithoutPenalty;
    private final BigDecimal immaturePrincipalAmount;
    private final LocalDateTime asOf;
    private final List<LeftAmountItem> leftAmounts;

    public CustomerWithdrawalAvailabilityResponseDTO(
            BigDecimal cashAmount,
            BigDecimal provisionalAmount,
            BigDecimal requestableAmount,
            BigDecimal earningsAmount,
            BigDecimal withdrawableWithoutPenalty,
            BigDecimal immaturePrincipalAmount,
            LocalDateTime asOf,
            List<LeftAmountItem> leftAmounts) {
        this.cashAmount = cashAmount;
        this.provisionalAmount = provisionalAmount;
        this.requestableAmount = requestableAmount;
        this.earningsAmount = earningsAmount;
        this.withdrawableWithoutPenalty = withdrawableWithoutPenalty;
        this.immaturePrincipalAmount = immaturePrincipalAmount;
        this.asOf = asOf;
        this.leftAmounts = leftAmounts;
    }

    @Getter
    public static class LeftAmountItem {
        private final LocalDateTime finalAt;
        private final LocalDateTime maturesAt;
        private final BigDecimal curAmount;
        private final boolean matured;

        public LeftAmountItem(LocalDateTime finalAt, BigDecimal curAmount, boolean matured) {
            this.finalAt = finalAt;
            this.maturesAt = finalAt.plusYears(1);
            this.curAmount = curAmount;
            this.matured = matured;
        }
    }
}
