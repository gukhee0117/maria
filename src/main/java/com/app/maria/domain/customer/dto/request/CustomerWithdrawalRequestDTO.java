package com.app.maria.domain.customer.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Getter
public class CustomerWithdrawalRequestDTO {

    @NotNull(message = "인출 요청금액은 필수입니다.")
    @Positive(message = "인출 요청금액은 0보다 커야 합니다.")
    private BigDecimal requestedAmount;

    @NotNull(message = "인출 목적지 일반계좌 ID는 필수입니다.")
    @Positive(message = "인출 목적지 일반계좌 ID는 0보다 커야 합니다.")
    private Long destinationGeneralAccountId;

    private boolean earlyWithdrawalAgreed;
}
